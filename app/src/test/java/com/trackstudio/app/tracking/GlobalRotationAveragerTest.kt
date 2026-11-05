package com.trackstudio.app.tracking

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class GlobalRotationAveragerTest {
    @Test fun recoversConsistentRotationsFromRedundantViewGraph() {
        val expected = mapOf(0 to rot(0.0,0.0,0.0), 1 to rot(0.0,0.12,0.0), 2 to rot(0.08,0.25,0.02), 3 to rot(0.12,0.38,-0.03))
        val edges = listOf(edge(0,1,expected),edge(1,2,expected),edge(2,3,expected),edge(0,2,expected),edge(1,3,expected))
        val solved = GlobalRotationAverager.solve(expected.keys.toList(), edges)!!
        expected.forEach { (frame, truth) ->
            val error = relativeAngle(solved.rotationsByFrame.getValue(frame), truth)
            assertTrue("frame=$frame error=$error", error < 0.01)
        }
        assertEquals(5, solved.acceptedEdges.size)
    }

    @Test fun robustlyRejectsBadRelativeRotationEdge() {
        val expected = mapOf(0 to rot(0.0,0.0,0.0), 1 to rot(0.0,0.1,0.0), 2 to rot(0.0,0.2,0.0), 3 to rot(0.0,0.3,0.0))
        val edges = mutableListOf(edge(0,1,expected),edge(1,2,expected),edge(2,3,expected),edge(0,2,expected),edge(0,3,expected))
        edges += GlobalRotationAverager.Edge(0,3,rot(0.0,1.1,0.0),120)
        val solved = GlobalRotationAverager.solve(expected.keys.toList(), edges)!!
        assertTrue(solved.rejectedEdges.isNotEmpty())
        assertTrue(relativeAngle(solved.rotationsByFrame.getValue(3), expected.getValue(3)) < 0.08)
    }

    @Test fun ignoresDisconnectedFramesAndUsesLargestComponent() {
        val expected = mapOf(0 to rot(0.0,0.0,0.0), 1 to rot(0.0,0.1,0.0), 2 to rot(0.0,0.2,0.0), 8 to rot(0.0,0.0,0.0))
        val solved = GlobalRotationAverager.solve(expected.keys.toList(), listOf(edge(0,1,expected),edge(1,2,expected)))!!
        assertEquals(setOf(0,1,2), solved.rotationsByFrame.keys)
    }

    private fun edge(a:Int,b:Int,truth:Map<Int,DoubleArray>) =
        GlobalRotationAverager.Edge(a,b,multiply(truth.getValue(b),transpose(truth.getValue(a))),100)

    private fun rot(x:Double,y:Double,z:Double):DoubleArray {
        fun axis(v:DoubleArray):DoubleArray {
            val t=sqrt(v.sumOf{it*it}); if(t<1e-12)return doubleArrayOf(1.0,0.0,0.0,0.0)
            val s=sin(t/2)/t
            return doubleArrayOf(cos(t/2),v[0]*s,v[1]*s,v[2]*s)
        }
        val q=axis(doubleArrayOf(x,y,z)); val w=q[0]; val a=q[1]; val b=q[2]; val c=q[3]
        return doubleArrayOf(1-2*(b*b+c*c),2*(a*b-c*w),2*(a*c+b*w),2*(a*b+c*w),1-2*(a*a+c*c),2*(b*c-a*w),2*(a*c-b*w),2*(b*c+a*w),1-2*(a*a+b*b))
    }
    private fun transpose(a:DoubleArray)=doubleArrayOf(a[0],a[3],a[6],a[1],a[4],a[7],a[2],a[5],a[8])
    private fun multiply(a:DoubleArray,b:DoubleArray):DoubleArray {
        val o=DoubleArray(9); for(r in 0..2)for(c in 0..2)for(k in 0..2)o[r*3+c]+=a[r*3+k]*b[k*3+c]; return o
    }
    private fun relativeAngle(a:DoubleArray,b:DoubleArray):Double {
        val d=multiply(a,transpose(b)); return acos(((d[0]+d[4]+d[8]-1)*0.5).coerceIn(-1.0,1.0))
    }
}
