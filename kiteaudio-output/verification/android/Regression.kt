import io.github.yuroyami.kiteaudio.core.AudioFormat
import io.github.yuroyami.kiteaudio.core.ChannelLayout
import io.github.yuroyami.kiteaudio.output.*
import android.media.AudioTrack
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private fun await(latch: CountDownLatch) { check(latch.await(3, TimeUnit.SECONDS)) { "timed out" } }
private fun rejects(action: () -> Unit) { check(runCatching(action).exceptionOrNull() is IllegalStateException) }
fun main() {
    var tests = 0
    for (frames in listOf(-1, 0, Int.MAX_VALUE)) {
        check(runCatching { openAudioOutput(AudioFormat(48_000, ChannelLayout.STEREO), frames) }.exceptionOrNull() is IllegalArgumentException)
    }
    tests++
    val accepted = mutableListOf<Short>()
    val ended = CountDownLatch(1)
    val renders = AtomicInteger()
    var transfers = 0
    AudioTrack.writer = { data, offset, _ ->
        if (transfers++ == 1) 0
        else { synchronized(accepted) { accepted += data[offset]; accepted += data[offset + 1] }; 2 }
    }
    val partial = openAudioOutput(AudioFormat(48_000, ChannelLayout.STEREO), 4) { if (it is AudioOutputEvent.Stopped) ended.countDown() }
    partial.start { out, frames ->
        if (renders.incrementAndGet() == 1) { for (i in out.indices) out[i] = (i + 1) / 16f; frames }
        else { partial.stop(); frames }
    }
    await(ended)
    check(accepted == (1..8).map { (it / 16f * 32767f).toInt().toShort() }) { "short writes lost or duplicated samples: $accepted" }
    check(AudioTrack.last.writes.get() == 5)
    partial.close()
    tests++
    AudioTrack.writer = { _, _, _ -> 0 }
    val failed = CountDownLatch(1)
    val zero = openAudioOutput(AudioFormat(48_000, ChannelLayout.STEREO), 4) { if (it is AudioOutputEvent.DeviceLost) failed.countDown() }
    zero.start { _, frames -> frames }
    await(failed)
    check(!zero.isRunning && AudioTrack.last.writes.get() == 10)
    zero.close()
    tests++
    AudioTrack.writer = { _, _, count -> Thread.sleep(1); count }
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val exited = CountDownLatch(1)
    val old = AtomicInteger()
    val blocked = openAudioOutput(AudioFormat(48_000, ChannelLayout.STEREO), 4) { if (it is AudioOutputEvent.Stopped) exited.countDown() }
    blocked.start { _, n -> old.incrementAndGet(); entered.countDown(); await(release); n }
    await(entered)
    blocked.stop()
    rejects { blocked.start { _, n -> n } }
    release.countDown()
    await(exited)
    check(old.get() == 1 && AudioTrack.last.writes.get() == 0)
    blocked.close()
    rejects { blocked.start { _, n -> n } }
    tests++
    val retried = CountDownLatch(1)
    val startFailure = openAudioOutput(AudioFormat(48_000, ChannelLayout.STEREO), 4) { error("observer failure") }
    AudioTrack.throwPlay = true
    startFailure.start { _, n -> n }
    check(!startFailure.isRunning)
    AudioTrack.throwPlay = false
    startFailure.start { _, _ -> retried.countDown(); 0 }
    await(retried)
    check(startFailure.isRunning)
    startFailure.close()
    tests++
    val invalidEnded = CountDownLatch(1)
    val invalid = openAudioOutput(AudioFormat(48_000, ChannelLayout.STEREO), 4) { if (it is AudioOutputEvent.Stopped) invalidEnded.countDown() }
    invalid.start { _, _ -> Int.MIN_VALUE }
    await(invalidEnded)
    check(!invalid.isRunning && AudioTrack.last.writes.get() == 0)
    invalid.close()
    tests++
    val inWrite = CountDownLatch(1)
    val pause = CountDownLatch(1)
    AudioTrack.onPause = { pause.countDown() }
    AudioTrack.writer = { _, _, _ -> inWrite.countDown(); await(pause); 0 }
    val writing = openAudioOutput(AudioFormat(48_000, ChannelLayout.STEREO), 4)
    writing.start { _, n -> n }
    await(inWrite)
    writing.stop()
    check(!writing.isRunning)
    writing.close()
    tests++
    println("PASS $tests Android source-copy regression groups; framework stubs only, no Android runtime or hardware")
}
