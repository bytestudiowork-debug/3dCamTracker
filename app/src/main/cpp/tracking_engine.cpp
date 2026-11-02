#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <limits>
#include <vector>
#include <utility>

#define TRACK_LOG_TAG "TrackStudioTracking"
#define TRACK_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TRACK_LOG_TAG, __VA_ARGS__)

namespace {

struct GrayImage {
    int width = 0;
    int height = 0;
    std::vector<float> pixels;

    float at(int x, int y) const {
        return pixels[static_cast<size_t>(y) * width + x];
    }
};

bool readGray(JNIEnv *env, jobject bitmap, GrayImage &out) {
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return false;
    }
    if (info.width < 16 || info.height < 16 ||
        (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 &&
         info.format != ANDROID_BITMAP_FORMAT_RGB_565)) {
        return false;
    }

    void *raw = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &raw) != ANDROID_BITMAP_RESULT_SUCCESS ||
        raw == nullptr) {
        return false;
    }

    out.width = static_cast<int>(info.width);
    out.height = static_cast<int>(info.height);
    out.pixels.resize(static_cast<size_t>(out.width) * out.height);

    const auto *base = static_cast<const uint8_t *>(raw);
    for (int y = 0; y < out.height; ++y) {
        const uint8_t *row = base + static_cast<size_t>(y) * info.stride;
        for (int x = 0; x < out.width; ++x) {
            float r, g, b;
            if (info.format == ANDROID_BITMAP_FORMAT_RGBA_8888) {
                const uint8_t *p = row + x * 4;
                r = p[0];
                g = p[1];
                b = p[2];
            } else {
                uint16_t pixel;
                std::memcpy(&pixel, row + x * 2, sizeof(pixel));
                r = static_cast<float>((pixel >> 11) & 0x1f) * (255.0f / 31.0f);
                g = static_cast<float>((pixel >> 5) & 0x3f) * (255.0f / 63.0f);
                b = static_cast<float>(pixel & 0x1f) * (255.0f / 31.0f);
            }
            out.pixels[static_cast<size_t>(y) * out.width + x] =
                0.299f * r + 0.587f * g + 0.114f * b;
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    return true;
}

GrayImage downsample(const GrayImage &source) {
    GrayImage result;
    result.width = std::max(1, source.width / 2);
    result.height = std::max(1, source.height / 2);
    result.pixels.resize(static_cast<size_t>(result.width) * result.height);
    for (int y = 0; y < result.height; ++y) {
        for (int x = 0; x < result.width; ++x) {
            const int sx = x * 2;
            const int sy = y * 2;
            result.pixels[static_cast<size_t>(y) * result.width + x] =
                (source.at(sx, sy) + source.at(std::min(sx + 1, source.width - 1), sy) +
                 source.at(sx, std::min(sy + 1, source.height - 1)) +
                 source.at(std::min(sx + 1, source.width - 1),
                           std::min(sy + 1, source.height - 1))) * 0.25f;
        }
    }
    return result;
}

float bilinear(const GrayImage &image, float x, float y) {
    const int x0 = static_cast<int>(std::floor(x));
    const int y0 = static_cast<int>(std::floor(y));
    const float dx = x - x0;
    const float dy = y - y0;
    return image.at(x0, y0) * (1.0f - dx) * (1.0f - dy) +
           image.at(x0 + 1, y0) * dx * (1.0f - dy) +
           image.at(x0, y0 + 1) * (1.0f - dx) * dy +
           image.at(x0 + 1, y0 + 1) * dx * dy;
}

struct Corner {
    float score;
    int x;
    int y;
};

} // namespace

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_trackstudio_app_tracking_nativebridge_TrackingNative_nativeDetectFeatures(
    JNIEnv *env, jobject, jobject bitmap, jint maxFeatures,
    jfloat quality, jfloat minimumDistance) {

    GrayImage image;
    if (!readGray(env, bitmap, image)) {
        return env->NewFloatArray(0);
    }

    const int margin = 4;
    float maximumScore = 0.0f;
    std::vector<Corner> corners;
    corners.reserve(static_cast<size_t>(image.width) * image.height / 8);

    for (int y = margin; y < image.height - margin; y += 2) {
        for (int x = margin; x < image.width - margin; x += 2) {
            float xx = 0.0f, yy = 0.0f, xy = 0.0f;
            for (int wy = -2; wy <= 2; ++wy) {
                for (int wx = -2; wx <= 2; ++wx) {
                    const float gx = image.at(x + wx + 1, y + wy) -
                                     image.at(x + wx - 1, y + wy);
                    const float gy = image.at(x + wx, y + wy + 1) -
                                     image.at(x + wx, y + wy - 1);
                    xx += gx * gx;
                    yy += gy * gy;
                    xy += gx * gy;
                }
            }
            const float trace = xx + yy;
            const float discriminant = std::sqrt(std::max(
                0.0f, (xx - yy) * (xx - yy) + 4.0f * xy * xy));
            const float score = 0.5f * (trace - discriminant);
            if (score > 0.0f) {
                corners.push_back({score, x, y});
                maximumScore = std::max(maximumScore, score);
            }
        }
    }

    const float threshold = maximumScore * std::max(0.0001f, quality);
    corners.erase(std::remove_if(corners.begin(), corners.end(),
        [threshold](const Corner &corner) { return corner.score < threshold; }),
        corners.end());
    std::sort(corners.begin(), corners.end(),
        [](const Corner &a, const Corner &b) { return a.score > b.score; });

    std::vector<float> selected;
    selected.reserve(static_cast<size_t>(std::max(0, maxFeatures)) * 2);
    const float minDistanceSquared = minimumDistance * minimumDistance;
    for (const Corner &corner : corners) {
        bool separated = true;
        for (size_t i = 0; i < selected.size(); i += 2) {
            const float dx = selected[i] - corner.x;
            const float dy = selected[i + 1] - corner.y;
            if (dx * dx + dy * dy < minDistanceSquared) {
                separated = false;
                break;
            }
        }
        if (separated) {
            selected.push_back(static_cast<float>(corner.x));
            selected.push_back(static_cast<float>(corner.y));
            if (static_cast<int>(selected.size() / 2) >= maxFeatures) break;
        }
    }

    jfloatArray result = env->NewFloatArray(static_cast<jsize>(selected.size()));
    if (result != nullptr && !selected.empty()) {
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(selected.size()),
                                 selected.data());
    }
    return result;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_trackstudio_app_tracking_nativebridge_TrackingNative_nativeTrackPoints(
    JNIEnv *env, jobject, jobject previousBitmap, jobject currentBitmap,
    jfloatArray pointArray, jint windowSize, jint pyramidLevels) {

    GrayImage previous, current;
    if (!readGray(env, previousBitmap, previous) ||
        !readGray(env, currentBitmap, current) ||
        previous.width != current.width || previous.height != current.height) {
        return env->NewFloatArray(0);
    }

    const jsize valueCount = env->GetArrayLength(pointArray);
    if (valueCount % 2 != 0 || valueCount > 20000) return env->NewFloatArray(0);
    std::vector<float> input(static_cast<size_t>(valueCount));
    env->GetFloatArrayRegion(pointArray, 0, valueCount, input.data());

    std::vector<GrayImage> prevPyramid{std::move(previous)};
    std::vector<GrayImage> currPyramid{std::move(current)};
    const int levels = std::max(1, std::min(6, static_cast<int>(pyramidLevels)));
    for (int level = 1; level < levels; ++level) {
        if (prevPyramid.back().width < 48 || prevPyramid.back().height < 48) break;
        prevPyramid.push_back(downsample(prevPyramid.back()));
        currPyramid.push_back(downsample(currPyramid.back()));
    }

    const int radius = std::max(3, std::min(25, static_cast<int>(windowSize) / 2));
    const int iterations = 12;
    const int actualLevels = static_cast<int>(prevPyramid.size());
    std::vector<float> output(static_cast<size_t>(valueCount / 2) * 4, 0.0f);

    for (jsize pointIndex = 0; pointIndex < valueCount / 2; ++pointIndex) {
        const float originalX = input[static_cast<size_t>(pointIndex) * 2];
        const float originalY = input[static_cast<size_t>(pointIndex) * 2 + 1];
        float trackedX = 0.0f, trackedY = 0.0f;
        float finalError = std::numeric_limits<float>::infinity();
        bool valid = std::isfinite(originalX) && std::isfinite(originalY);

        for (int level = actualLevels - 1; level >= 0 && valid; --level) {
            const GrayImage &a = prevPyramid[level];
            const GrayImage &b = currPyramid[level];
            const float scale = static_cast<float>(1 << level);
            const float px = originalX / scale;
            const float py = originalY / scale;
            if (level == actualLevels - 1) {
                trackedX = px;
                trackedY = py;
            } else {
                trackedX *= 2.0f;
                trackedY *= 2.0f;
            }

            if (px < radius + 2 || py < radius + 2 ||
                px >= a.width - radius - 2 || py >= a.height - radius - 2) {
                valid = false;
                break;
            }

            for (int iteration = 0; iteration < iterations; ++iteration) {
                double gxx = 0.0, gxy = 0.0, gyy = 0.0;
                double bx = 0.0, by = 0.0, errorSum = 0.0;
                int samples = 0;

                if (trackedX < radius + 2 || trackedY < radius + 2 ||
                    trackedX >= b.width - radius - 2 ||
                    trackedY >= b.height - radius - 2) {
                    valid = false;
                    break;
                }

                for (int wy = -radius; wy <= radius; ++wy) {
                    for (int wx = -radius; wx <= radius; ++wx) {
                        const float qx = trackedX + wx;
                        const float qy = trackedY + wy;
                        const float gx = 0.5f * (bilinear(b, qx + 1, qy) -
                                                 bilinear(b, qx - 1, qy));
                        const float gy = 0.5f * (bilinear(b, qx, qy + 1) -
                                                 bilinear(b, qx, qy - 1));
                        const float difference =
                            bilinear(b, qx, qy) - a.at(
                                static_cast<int>(px) + wx, static_cast<int>(py) + wy);
                        gxx += gx * gx;
                        gxy += gx * gy;
                        gyy += gy * gy;
                        bx += gx * difference;
                        by += gy * difference;
                        errorSum += std::fabs(difference);
                        ++samples;
                    }
                }

                const double determinant = gxx * gyy - gxy * gxy;
                if (determinant < 1e-4 || samples == 0) {
                    valid = false;
                    break;
                }

                const float dx = static_cast<float>((gyy * bx - gxy * by) / determinant);
                const float dy = static_cast<float>((gxx * by - gxy * bx) / determinant);
                trackedX -= dx;
                trackedY -= dy;
                finalError = static_cast<float>(errorSum / samples);
                if (dx * dx + dy * dy < 0.0004f) break;
            }
        }

        const size_t out = static_cast<size_t>(pointIndex) * 4;
        output[out] = trackedX;
        output[out + 1] = trackedY;
        output[out + 2] = finalError;
        output[out + 3] = valid ? 1.0f : 0.0f;
    }

    jfloatArray result = env->NewFloatArray(static_cast<jsize>(output.size()));
    if (result != nullptr && !output.empty()) {
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(output.size()),
                                 output.data());
    }
    return result;
}
