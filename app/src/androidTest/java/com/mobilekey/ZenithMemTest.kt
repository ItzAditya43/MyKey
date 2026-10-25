package com.mobilekey

import ai.onnxruntime.OrtSession.SessionOptions.OptLevel
import android.os.Bundle
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilekey.engine.ZenithModel
import org.junit.Test
import org.junit.runner.RunWith

/** Memory per session option, run one at a time (-e opt N) in a fresh process. */
@RunWith(AndroidJUnit4::class)
class ZenithMemTest {
    @Test fun memory() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val opt = InstrumentationRegistry.getArguments().getString("opt", "0").toInt()
        val (level, arena) = listOf(OptLevel.ALL_OPT to true, OptLevel.ALL_OPT to false, OptLevel.BASIC_OPT to true, OptLevel.NO_OPT to false)[opt % 4]
        val prepack = true
        val threads = InstrumentationRegistry.getArguments().getString("threads", "2").toInt()
        System.gc()
        val before = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val m = ZenithModel.load(inst.targetContext, threads = threads, level = level, arena = arena, prepack = prepack)
        val zero = ZenithModel.State(IntArray(0), FloatArray(ZenithModel.KV), FloatArray(ZenithModel.KV), FloatArray(0))
        val ids = m.contextIds("en", "Hey, I was thinking we could meet tomorrow after work and maybe grab some dinner")
        repeat(3) { m.feed(zero, ids) }
        var s = m.feed(zero, ids.copyOf(5)).first
        val t0 = System.nanoTime()
        for (i in 5 until ids.size) s = m.feed(s, intArrayOf(ids[i])).first
        val step = (System.nanoTime() - t0) / 1e6 / (ids.size - 5)
        val after = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val msg = "threads=$threads opt=$level load=${m.loadMs}ms step=%.1fms PSS +%dMB native +%dMB".format(step,
            (after.totalPss - before.totalPss) / 1024, (after.nativePss - before.nativePss) / 1024)
        inst.sendStatus(0, Bundle().apply { putString("zenith", msg) })
    }
}
