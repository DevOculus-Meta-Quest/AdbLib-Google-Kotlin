/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.adblib.impl

import com.android.adblib.AdbFeatures
import com.android.adblib.AdbInputChannel
import com.android.adblib.AdbLibProperties
import com.android.adblib.AdbSession
import com.android.adblib.DeviceSelector
import com.android.adblib.INFINITE_DURATION
import com.android.adblib.ShellCollector
import com.android.adblib.ShellCommand
import com.android.adblib.ShellCommand.Protocol
import com.android.adblib.ShellV2Collector
import com.android.adblib.adbLogger
import com.android.adblib.availableFeatures
import com.android.adblib.deviceProperties
import com.android.adblib.impl.ShellWithIdleMonitoring.Parameters
import com.android.adblib.property
import com.android.adblib.utils.SuspendingLazy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.time.Duration

internal class ShellCommandImpl<T>(
  override val session: AdbSession,
  private val device: DeviceSelector,
  private val command: String,
) : ShellCommand<T> {

    private val logger = adbLogger(session)

    private var _allowStripCrLfForLegacyShell: Boolean = true
    private var _allowLegacyShell: Boolean = true
    private var _allowLegacyExec: Boolean = false
    private var _allowShellV2: Boolean = true
    private var collector: ShellV2Collector<T>? = null
    private var commandTimeout: Duration = INFINITE_DURATION
    private var commandOutputTimeout: Duration? = null
    private var commandOverride: ((String, Protocol) -> String)? = null
    private var stdinChannel: AdbInputChannel? = null
    private var _shutdownOutputForLegacyShell: Boolean = true
    private var bufferSize: Int = session.property(AdbLibProperties.DEFAULT_SHELL_BUFFER_SIZE)

    override fun <U> withCollector(collector: ShellV2Collector<U>): ShellCommand<U> {
        @Suppress("UNCHECKED_CAST")
        val result = this as ShellCommandImpl<U>

        result.collector = collector
        return result
    }

    override fun <U> withLegacyCollector(collector: ShellCollector<U>): ShellCommand<U> {
        @Suppress("UNCHECKED_CAST")
        val result = this as ShellCommandImpl<U>

        result.collector = ShellCommandHelpers.mapToShellV2Collector(collector)
        return result
    }

    override fun withStdin(stdinChannel: AdbInputChannel?): ShellCommand<T> {
        this.stdinChannel = stdinChannel
        return this
    }

    override fun withCommandTimeout(timeout: Duration): ShellCommand<T> {
        this.commandTimeout = timeout
        return this
    }

    override fun withCommandOutputTimeout(timeout: Duration): ShellCommand<T> {
        this.commandOutputTimeout = timeout
        return this
    }

    override fun withBufferSize(size: Int): ShellCommand<T> {
        this.bufferSize = size
        return this
    }

    override fun allowShellV2(value: Boolean): ShellCommand<T> {
        this._allowShellV2 = value
        return this
    }

    override fun allowLegacyExec(value: Boolean): ShellCommand<T> {
        this._allowLegacyExec = value
        return this
    }

    override fun allowLegacyShell(value: Boolean): ShellCommand<T> {
        this._allowLegacyShell = value
        return this
    }

    override fun forceShellV2(): ShellCommand<T> {
        this._allowShellV2 = true
        this._allowLegacyExec = false
        this._allowLegacyShell = false
        return this
    }

    override fun forceLegacyExec(): ShellCommand<T> {
        this._allowShellV2 = false
        this._allowLegacyExec = true
        this._allowLegacyShell = false
        return this
    }

    override fun forceLegacyShell(): ShellCommand<T> {
        this._allowShellV2 = false
        this._allowLegacyExec = false
        this._allowLegacyShell = true
        return this
    }

    override fun shutdownOutputForLegacyShell(shutdownOutput: Boolean): ShellCommand<T> {
        this._shutdownOutputForLegacyShell = shutdownOutput
        return this
    }

    override fun allowStripCrLfForLegacyShell(value: Boolean): ShellCommand<T> {
        this._allowStripCrLfForLegacyShell = value
        return this
    }

    override fun withCommandOverride(commandOverride: (String, Protocol) -> String): ShellCommand<T> {
        this.commandOverride = commandOverride
        return this
    }

    override fun execute() = flow {
        shellFlow().collect {
            emit(it)
        }
    }

    override suspend fun <R> executeAsSingleOutput(block: suspend (T) -> R): R {
        val collector = collector ?: throw IllegalArgumentException("Shell Collector is not set")
        require(collector.isSingleOutputCollector) {
            "Shell Collector '$collector' is not a single output collector"
        }
        return execute().map { singleOutput ->
            try {
                block(singleOutput)
            } finally {
                (singleOutput as? AutoCloseable)?.close()
            }
        }.first()
    }

    private suspend fun shellFlow(): Flow<T> {
        val collector = collector ?: throw IllegalArgumentException("Collector is not set")

        val protocol = pickProtocol()
        val commandOutputTimeout = this.commandOutputTimeout
        val command = commandOverride?.invoke(command, protocol) ?: command
        val stripCrLf = SuspendingLazy {
            (protocol == Protocol.SHELL) &&
                    _allowStripCrLfForLegacyShell &&
                    (session.deviceServices.deviceProperties(device).api() <= 23)
        }
        return if (commandOutputTimeout != null) {
            logger.debug { "Executing command with protocol=$protocol and commandOutputTimeout=$commandOutputTimeout: $command" }
            when (protocol) {
                Protocol.SHELL_V2 -> {
                    ShellV2WithIdleMonitoring(
                        Parameters(
                            deviceServices = session.deviceServices,
                            device = device,
                            command = command,
                            shellCollector = collector,
                            stdinChannel = stdinChannel,
                            commandTimeout = commandTimeout,
                            commandOutputTimeout = commandOutputTimeout,
                            bufferSize = bufferSize,
                            stripCrLf = false,
                            shutdownOutput = false
                        )
                    ).createFlow()
                }
                Protocol.EXEC -> {
                    LegacyExecWithIdleMonitoring(
                        Parameters(
                            deviceServices = session.deviceServices,
                            device = device,
                            command = command,
                            shellCollector = ShellCommandHelpers.mapToLegacyCollector(collector),
                            stdinChannel = stdinChannel,
                            commandTimeout = commandTimeout,
                            commandOutputTimeout = commandOutputTimeout,
                            bufferSize = bufferSize,
                            stripCrLf = false,
                            shutdownOutput = _shutdownOutputForLegacyShell
                        )
                    ).createFlow()
                }
                Protocol.SHELL -> {
                    LegacyShellWithIdleMonitoring(
                        Parameters(
                            deviceServices = session.deviceServices,
                            device = device,
                            command = command,
                            shellCollector = ShellCommandHelpers.mapToLegacyCollector(collector),
                            stdinChannel = stdinChannel,
                            commandTimeout = commandTimeout,
                            commandOutputTimeout = commandOutputTimeout,
                            bufferSize = bufferSize,
                            stripCrLf = stripCrLf.value(),
                            shutdownOutput = _shutdownOutputForLegacyShell
                        )
                    ).createFlow()
                }
            }
        } else {
            logger.debug { "Executing command with protocol=$protocol: $command" }
            when (protocol) {
                Protocol.SHELL_V2 -> {
                    session.deviceServices.shellV2(
                        device = device,
                        command = command,
                        shellCollector = collector,
                        stdinChannel = stdinChannel,
                        commandTimeout = commandTimeout,
                        bufferSize = bufferSize
                    )
                }
                Protocol.EXEC -> {
                    session.deviceServices.exec(
                        device = device,
                        command = command,
                        shellCollector = ShellCommandHelpers.mapToLegacyCollector(collector),
                        stdinChannel = stdinChannel,
                        commandTimeout = commandTimeout,
                        bufferSize = bufferSize,
                        shutdownOutput = _shutdownOutputForLegacyShell
                    )
                }
                Protocol.SHELL -> {
                    session.deviceServices.shell(
                        device = device,
                        command = command,
                        shellCollector = ShellCommandHelpers.mapToLegacyCollector(collector),
                        stdinChannel = stdinChannel,
                        commandTimeout = commandTimeout,
                        bufferSize = bufferSize,
                        stripCrLf = stripCrLf.value(),
                        shutdownOutput = _shutdownOutputForLegacyShell
                    )
                }
            }
        }
    }

    private suspend fun pickProtocol(): Protocol {
        val shellV2Supported = SuspendingLazy {
            // Shell V2 support is exposed as a device (and ADB feature).
            session.hostServices.availableFeatures(device).contains(AdbFeatures.SHELL_V2)
        }
        val execSupported = SuspendingLazy {
            // Exec support was added in API 21 (Lollipop)
            session.deviceServices.deviceProperties(device).api() >= 21
        }
        val protocol = when {
            _allowShellV2 && shellV2Supported.value() -> Protocol.SHELL_V2
            _allowLegacyExec && execSupported.value() -> Protocol.EXEC
            _allowLegacyShell -> Protocol.SHELL
            else -> throw IllegalArgumentException("No compatible shell protocol is supported or allowed")
        }
        return protocol
    }
}
