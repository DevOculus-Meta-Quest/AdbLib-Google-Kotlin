/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.adblib.impl.channels

import com.android.adblib.read
import com.android.adblib.testingutils.CloseablesRule
import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.testingutils.FakeAdbServerProvider
import com.android.adblib.testingutils.TestingAdbSessionHost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousSocketChannel
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals

class AdbSocketChannelImplTest {

    @JvmField
    @Rule
    val closeables = CloseablesRule()

    private fun <T : AutoCloseable> registerCloseable(item: T): T {
        return closeables.register(item)
    }

    @JvmField
    @Rule
    var exceptionRule: ExpectedException = ExpectedException.none()

    @Test
    fun coroutineCancellationClosesSocketChannel() {
        // Prepare

        // Note: We don't really need fake adb for this test, we just need a socket server that
        // does nothing, but we get that for free with fake adb.
        val fakeAdb = registerCloseable(FakeAdbServerProvider().buildDefault().start())
        val host = registerCloseable(TestingAdbSessionHost())
        val socketChannel = AsynchronousSocketChannel.open(host.asynchronousChannelGroup)
        val channel = AdbSocketChannelImpl(host, socketChannel)

        // Act
        val shortTimeoutMs = 10L
        val longTimeoutMs = 10_000L
        var expectedException: Throwable? = null
        runBlocking {
            channel.connect(fakeAdb.socketAddress, longTimeoutMs, TimeUnit.MILLISECONDS)

            // We will be attempting to read some data from the fake adb server. Since we have not
            // send any request, we should not be receiving any data, so we know we are exercising
            // the timeout behavior.
            // Also, by using withTimeout with a much smaller timeout than the "read" timeout,
            // we ensure that we hit the coroutine cancellation code path, as opposed to the
            // underlying AsynchronousSocketChannel.read timeout path.
            try {
                withTimeout(shortTimeoutMs) {
                    val buffer = ByteBuffer.allocate(1_024)
                    channel.read(buffer, longTimeoutMs, TimeUnit.MILLISECONDS)
                }
            } catch (t: TimeoutCancellationException) {
                expectedException = t
            }
        }

        // Assert

        // By expecting the kotlinx.coroutines.TimeoutCancellationException and nothing else
        // we ensure that we hit the withTimeout coroutine cancellation path.
        Assert.assertNotNull(expectedException)
        Assert.assertTrue(expectedException is TimeoutCancellationException)

        // This ensures that the underlying socket channel has been closed during cancellation,
        // i.e. that all asynchronous socket channel operations have been terminated and the
        // implementation does not leak those operations.
        Assert.assertFalse(channel.isOpen)
    }

    @Test
    fun childCoroutineCancellationIsObservableFromParentScope(): Unit = runBlockingWithTimeout {
        // Prepare
        // Note: We don't really need fake adb for this test, we just need a socket server that
        // does nothing, but we get that for free with fake adb.
        val fakeAdb = registerCloseable(FakeAdbServerProvider().buildDefault().start())
        val host = registerCloseable(TestingAdbSessionHost())
        val socketChannel = AsynchronousSocketChannel.open(host.asynchronousChannelGroup)
        val channel = AdbSocketChannelImpl(host, socketChannel)

        // Act
        exceptionRule.expect(CancellationException::class.java)
        exceptionRule.expectMessage("My Message")
        channel.connect(fakeAdb.socketAddress, 10_000, TimeUnit.MILLISECONDS)
        coroutineScope {
            val job1 = async {
                throw CancellationException("My Message")
            }
            val job2 = async {
                // Ensure there is (forever) pending read
                val buffer = ByteBuffer.allocate(100)
                channel.read(buffer)
            }
            awaitAll(job1, job2)
        }
    }

    @Test
    fun coroutineCancellationIsObservableFromParentScope(): Unit = runBlockingWithTimeout {
        // Prepare
        // Note: We don't really need fake adb for this test, we just need a socket server that
        // does nothing, but we get that for free with fake adb.
        val fakeAdb = registerCloseable(FakeAdbServerProvider().buildDefault().start())
        val host = registerCloseable(TestingAdbSessionHost())
        val socketChannel = AsynchronousSocketChannel.open(host.asynchronousChannelGroup)
        val channel = AdbSocketChannelImpl(host, socketChannel)

        // Act
        exceptionRule.expect(CancellationException::class.java)
        exceptionRule.expectMessage("My Message")
        channel.connect(fakeAdb.socketAddress, 10_000, TimeUnit.MILLISECONDS)
        coroutineScope {
            launch {
                // Ensure there is (forever) pending read
                val buffer = ByteBuffer.allocate(100)
                channel.read(buffer)
            }
            throw CancellationException("My Message")
        }
    }

    @Test
    fun readingFromAbruptlyClosingPeerReturnsMinusOne(): Unit = runBlockingWithTimeout {
        // Prepare
        // Note: We don't really need fake adb for this test, we just need a socket server that
        // does nothing, but we get that for free with fake adb.
        val fakeAdb = registerCloseable(FakeAdbServerProvider().buildDefault().start())
        val host = registerCloseable(TestingAdbSessionHost())
        val socketChannel = AsynchronousSocketChannel.open(host.asynchronousChannelGroup)
        val channel = registerCloseable(AdbSocketChannelImpl(host, socketChannel))

        // Act
        val connected = CompletableDeferred<Unit>()
        val deferredByteCount = async {
            channel.connect(fakeAdb.socketAddress, 10_000, TimeUnit.MILLISECONDS)
            connected.complete(Unit)
            val buffer = ByteBuffer.allocate(10)
            channel.read(buffer)
        }
        connected.await()

        launch {
            // Delay `fakeAdb.close()` for `channel.read()` to start
            delay(100)
            fakeAdb.close()
        }

        deferredByteCount.await()

        // Assert
        assertEquals(-1, deferredByteCount.await())
    }
}
