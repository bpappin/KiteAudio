package io.github.yuroyami.kiteaudio.midi.devices

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.midi.MidiDevice
import javax.sound.midi.MidiMessage
import javax.sound.midi.Receiver
import javax.sound.midi.Transmitter
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class JvmMidiLifecycleTest {
    private class FakeDevice : MidiDevice {
        val info = object : MidiDevice.Info("Fake", "KiteMIDI", "fixture", "1") {}
        val receiverEntered = CountDownLatch(1)
        val allowReceiver = CountDownLatch(1)
        val closes = AtomicInteger()
        val receiverCloses = AtomicInteger()
        private var open = false
        override fun getDeviceInfo() = info
        override fun open() { open = true }
        override fun close() { open = false; closes.incrementAndGet() }
        override fun isOpen() = open
        override fun getMicrosecondPosition() = -1L
        override fun getMaxReceivers() = -1
        override fun getMaxTransmitters() = 0
        override fun getReceiver(): Receiver {
            receiverEntered.countDown()
            check(allowReceiver.await(5, TimeUnit.SECONDS))
            return object : Receiver {
                override fun send(message: MidiMessage, timeStamp: Long) = Unit
                override fun close() { receiverCloses.incrementAndGet() }
            }
        }
        override fun getReceivers(): List<Receiver> = emptyList()
        override fun getTransmitter(): Transmitter = error("not an input")
        override fun getTransmitters(): List<Transmitter> = emptyList()
    }

    private class HeldDispatcher : CoroutineDispatcher() {
        val pending = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.add(block) }
        fun runOne() { checkNotNull(pending.poll()).run() }
    }

    @Test fun closeRejectsAnAcquisitionThatHasNotYetBeenAdopted() = runBlocking {
        val fake = FakeDevice()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val devices = JvmMidiDevices(scope, { arrayOf(fake.info) }, { fake })
        try {
            val port = withTimeout(5_000) { devices.ports.first { it.isNotEmpty() }.single() }
            val opening = async(Dispatchers.Default) { runCatching { devices.openOutput(port) } }
            assertTrue(fake.receiverEntered.await(5, TimeUnit.SECONDS))
            devices.close()
            fake.allowReceiver.countDown()
            assertTrue(withTimeout(5_000) { opening.await() }.exceptionOrNull() is MidiPortException)
            assertEquals(1, fake.closes.get())
            assertEquals(1, fake.receiverCloses.get())
        } finally {
            fake.allowReceiver.countDown()
            devices.close()
            scope.cancel()
        }
    }

    @Test fun cancellationClosesAResultWaitingForItsCallerDispatcher() = runBlocking {
        val fake = FakeDevice()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val devices = JvmMidiDevices(scope, { arrayOf(fake.info) }, { fake })
        val caller = HeldDispatcher()
        try {
            val port = withTimeout(5_000) { devices.ports.first { it.isNotEmpty() }.single() }
            val opening = launch(caller) { devices.openOutput(port) }
            caller.runOne()
            // The device holds its answer until the caller has suspended. An answer that is ready
            // before that returns with no dispatch, and the wait below would never end.
            assertTrue(fake.receiverEntered.await(5, TimeUnit.SECONDS))
            fake.allowReceiver.countDown()
            withTimeout(5_000) { while (caller.pending.isEmpty()) delay(1) }
            opening.cancel()
            caller.runOne()
            withTimeout(5_000) { opening.join() }
            assertEquals(1, fake.closes.get())
            assertEquals(1, fake.receiverCloses.get())
        } finally {
            fake.allowReceiver.countDown()
            devices.close()
            scope.cancel()
        }
    }
}
