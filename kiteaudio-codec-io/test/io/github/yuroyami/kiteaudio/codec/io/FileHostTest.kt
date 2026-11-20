package io.github.yuroyami.kiteaudio.codec.io

import io.github.yuroyami.kiteaudio.codec.UnsupportedFeatureException
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertTrue

class FileHostTest {
    @Test
    fun unavailableFilesAndHostsHaveNamedFailures() {
        val failure = assertFails { Path(".kiteaudio-codec-absent-${Random.nextLong().toULong().toString(16)}").openAudioSource() }
        assertTrue(failure is IOException || failure is UnsupportedFeatureException)
        if (failure is UnsupportedFeatureException) assertTrue(failure.feature.contains("seekable files"))
    }
}
