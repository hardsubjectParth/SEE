package com.example.see

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import audio.soniqo.speech.ModelManager
import audio.soniqo.speech.SpeechConfig
import audio.soniqo.speech.SpeechEvent
import audio.soniqo.speech.SpeechPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch


class MainActivity : ComponentActivity() {

    // =========================================================
    // AUDIO CONFIGURATION
    // =========================================================

    private val sampleRate = 16000

    private val channelConfig =
        AudioFormat.CHANNEL_IN_MONO

    private val audioFormat =
        AudioFormat.ENCODING_PCM_16BIT


    // =========================================================
    // AUDIO RECORDING
    // =========================================================

    private var audioRecord: AudioRecord? = null

    private var recordingThread: Thread? = null

    @Volatile
    private var isRecording = false


    // =========================================================
    // PARAKEET
    // =========================================================

    private var speechPipeline: SpeechPipeline? = null

    private var initializationJob: Job? = null

    private val appScope =
        CoroutineScope(Dispatchers.Main)


    // =========================================================
    // UI STATE
    // =========================================================

    private var statusText by mutableStateOf(
        "Ready"
    )

    private var transcriptText by mutableStateOf(
        "Transcript will appear here."
    )


    // =========================================================
    // MICROPHONE PERMISSION
    // =========================================================

    private val microphonePermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted) {

                initializeParakeet()

            } else {

                statusText =
                    "Microphone permission denied"
            }
        }


    // =========================================================
    // ACTIVITY CREATED
    // =========================================================

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(savedInstanceState)

        setContent {

            SpeechScreen(
                statusText = statusText,
                transcriptText = transcriptText,
                isRecording = isRecording,

                onStartStop = {

                    if (isRecording) {

                        stopEverything()

                    } else {

                        requestMicrophonePermission()
                    }
                }
            )
        }
    }


    // =========================================================
    // REQUEST MICROPHONE PERMISSION
    // =========================================================

    private fun requestMicrophonePermission() {

        val permission =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            )

        if (
            permission ==
            PackageManager.PERMISSION_GRANTED
        ) {

            initializeParakeet()

        } else {

            microphonePermissionLauncher.launch(
                Manifest.permission.RECORD_AUDIO
            )
        }
    }


    // =========================================================
    // INITIALIZE PARAKEET
    // =========================================================

    private fun initializeParakeet() {

        // Don't initialize twice.
        if (speechPipeline != null) {

            startMicrophone()

            return
        }

        // Don't start another initialization job.
        if (
            initializationJob?.isActive == true
        ) {
            return
        }

        statusText =
            "Preparing Parakeet..."


        initializationJob =
            appScope.launch(Dispatchers.IO) {

                try {

                    // =================================================
                    // STEP 1
                    // =================================================

                    updateStatus(
                        "Downloading/loading Parakeet model..."
                    )


                    // The SDK downloads the model automatically
                    // the first time this is called.
                    //
                    // This can take a while on the first run.

                    val modelDir =
                        ModelManager.ensureModels(
                            applicationContext
                        )


                    // =================================================
                    // STEP 2
                    // =================================================

                    updateStatus(
                        "Model ready"
                    )


                    // =================================================
                    // STEP 3
                    // =================================================

                    updateStatus(
                        "Creating Parakeet pipeline..."
                    )


                    val pipeline =
                        SpeechPipeline(
                            SpeechConfig(
                                modelDir = modelDir,
                                useNnapi = false
                            )
                        )


                    speechPipeline =
                        pipeline


                    // =================================================
                    // STEP 4
                    // =================================================

                    updateStatus(
                        "Starting Parakeet..."
                    )


                    // Listen to Parakeet events.
                    //
                    // This coroutine runs continuously while
                    // Parakeet is active.

                    launch {

                        pipeline.events.collect { event ->

                            when (event) {

                                is SpeechEvent.TranscriptionCompleted -> {

                                    val text =
                                        event.text.trim()

                                    if (text.isNotEmpty()) {

                                        runOnUiThread {

                                            transcriptText =
                                                text

                                            statusText =
                                                "Listening..."
                                        }
                                    }
                                }


                                is SpeechEvent.ResponseDone -> {

                                    runOnUiThread {

                                        statusText =
                                            "Listening..."
                                    }

                                    // Allow Parakeet to continue
                                    // listening after an utterance.
                                    try {

                                        pipeline.resumeListening()

                                    } catch (_: Exception) {
                                    }
                                }


                                else -> {
                                    // Other events aren't needed
                                    // for this first test.
                                }
                            }
                        }
                    }


                    // =================================================
                    // STEP 5
                    // =================================================

                    pipeline.start()


                    updateStatus(
                        "Parakeet ready"
                    )


                    // =================================================
                    // STEP 6
                    // =================================================

                    startMicrophone()


                } catch (e: Exception) {

                    e.printStackTrace()

                    runOnUiThread {

                        statusText =
                            "PARAKEET ERROR\n\n" +
                                    e.javaClass.simpleName +
                                    "\n\n" +
                                    (e.message ?: "Unknown error")
                    }
                }
            }
    }


    // =========================================================
    // START MICROPHONE
    // =========================================================

    private fun startMicrophone() {

        // ---------------------------------------------------------
        // Permission check
        // ---------------------------------------------------------

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            statusText =
                "Microphone permission required"

            return
        }


        // ---------------------------------------------------------
        // Buffer size
        // ---------------------------------------------------------

        val bufferSize =
            AudioRecord.getMinBufferSize(
                sampleRate,
                channelConfig,
                audioFormat
            )


        if (
            bufferSize == AudioRecord.ERROR ||
            bufferSize == AudioRecord.ERROR_BAD_VALUE
        ) {

            statusText =
                "Audio buffer initialization failed"

            return
        }


        // ---------------------------------------------------------
        // Create AudioRecord
        // ---------------------------------------------------------

        try {

            audioRecord =
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize * 2
                )

        } catch (e: SecurityException) {

            statusText =
                "Microphone permission error"

            return
        }


        // ---------------------------------------------------------
        // Verify initialization
        // ---------------------------------------------------------

        if (
            audioRecord?.state !=
            AudioRecord.STATE_INITIALIZED
        ) {

            statusText =
                "AudioRecord failed to initialize"

            audioRecord?.release()

            audioRecord = null

            return
        }


        // ---------------------------------------------------------
        // Start recording
        // ---------------------------------------------------------

        try {

            audioRecord?.startRecording()

        } catch (e: Exception) {

            statusText =
                "Could not start microphone"

            audioRecord?.release()

            audioRecord = null

            return
        }


        // ---------------------------------------------------------
        // State
        // ---------------------------------------------------------

        isRecording = true

        statusText =
            "Listening..."


        // ---------------------------------------------------------
        // Background microphone thread
        // ---------------------------------------------------------

        recordingThread =
            Thread {

                val shortBuffer =
                    ShortArray(bufferSize)


                while (isRecording) {

                    val sampleCount =
                        audioRecord?.read(
                            shortBuffer,
                            0,
                            shortBuffer.size
                        ) ?: 0


                    if (sampleCount <= 0) {

                        continue
                    }


                    // =================================================
                    // PCM16 → FLOAT32
                    // =================================================
                    //
                    // AudioRecord gives us:
                    //
                    // ShortArray
                    //
                    // Parakeet expects:
                    //
                    // FloatArray
                    //
                    // with values approximately:
                    //
                    // -1.0 to +1.0

                    val floatSamples =
                        FloatArray(sampleCount)


                    for (i in 0 until sampleCount) {

                        floatSamples[i] =
                            shortBuffer[i] / 32768.0f
                    }


                    // =================================================
                    // SEND AUDIO TO PARAKEET
                    // =================================================

                    try {

                        speechPipeline?.pushAudio(
                            floatSamples
                        )

                    } catch (e: Exception) {

                        runOnUiThread {

                            statusText =
                                "Audio pipeline error\n\n" +
                                        (e.message ?: "Unknown error")
                        }
                    }
                }
            }


        recordingThread?.start()
    }


    // =========================================================
    // STOP EVERYTHING
    // =========================================================

    private fun stopEverything() {

        // ---------------------------------------------------------
        // Stop recording loop
        // ---------------------------------------------------------

        isRecording = false


        // ---------------------------------------------------------
        // Stop AudioRecord
        // ---------------------------------------------------------

        try {

            audioRecord?.stop()

        } catch (_: Exception) {
        }


        // ---------------------------------------------------------
        // Release microphone
        // ---------------------------------------------------------

        try {

            audioRecord?.release()

        } catch (_: Exception) {
        }

        audioRecord = null


        // ---------------------------------------------------------
        // Wait for recording thread
        // ---------------------------------------------------------

        try {

            recordingThread?.join(500)

        } catch (_: InterruptedException) {
        }

        recordingThread = null


        // ---------------------------------------------------------
        // Stop Parakeet
        // ---------------------------------------------------------

        try {

            speechPipeline?.stop()

        } catch (_: Exception) {
        }


        statusText =
            "Stopped"
    }


    // =========================================================
    // UPDATE UI FROM BACKGROUND THREAD
    // =========================================================

    private fun updateStatus(
        message: String
    ) {

        runOnUiThread {

            statusText =
                message
        }
    }


    // =========================================================
    // ACTIVITY DESTROYED
    // =========================================================

    override fun onDestroy() {

        stopEverything()

        initializationJob?.cancel()

        appScope.cancel()

        super.onDestroy()
    }
}


// =================================================================
// UI
// =================================================================

@Composable
fun SpeechScreen(
    statusText: String,
    transcriptText: String,
    isRecording: Boolean,
    onStartStop: () -> Unit
) {

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(24.dp),

        horizontalAlignment =
            Alignment.CenterHorizontally,

        verticalArrangement =
            Arrangement.Center
    ) {

        // ---------------------------------------------------------
        // STATUS
        // ---------------------------------------------------------

        Text(
            text = statusText
        )


        Spacer(
            modifier =
                Modifier.height(32.dp)
        )


        // ---------------------------------------------------------
        // TRANSCRIPT
        // ---------------------------------------------------------

        Text(
            text = transcriptText,

            modifier =
                Modifier.fillMaxWidth()
        )


        Spacer(
            modifier =
                Modifier.height(32.dp)
        )


        // ---------------------------------------------------------
        // BUTTON
        // ---------------------------------------------------------

        Button(
            onClick = onStartStop
        ) {

            Text(
                text =
                    if (isRecording) {
                        "Stop Listening"
                    } else {
                        "Start Listening"
                    }
            )
        }
    }
}