package com.example.audioasandroid

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.*
import android.os.Bundle
import android.widget.Toast
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor
import androidx.compose.ui.Alignment
import androidx.compose.material3.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.audioasandroid.ui.theme.AudioasAndroidTheme
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.FileOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.min
import android.database.Cursor
import android.provider.OpenableColumns
import androidx.compose.ui.draw.rotate
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Color

data class musicwav(val type: String, val rawId: Int, var path: String, val name: String)

class MainActivity : ComponentActivity() {
    private var permissions: Array<String> = arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
    private val musicList = mutableStateListOf(
        musicwav("prepare", R.raw.anheqiao, "", "安河桥片段"),
        musicwav("prepare", R.raw.anheqiao48k_01, "", "安河桥片段超分"),
        musicwav("prepare", R.raw.p227_002_mic1_12_pr, "", "英语录音"),
        musicwav("prepare", R.raw.p227_002_mic1_16_pr, "", "英语录音超分"),
    )
    private lateinit var module_ori: Module
    private var inferenceTimeori: Long = 0
    private var sr = 48000
    private var numChannels = 1
    private var bitsPerSample = 16
    var showBottomSheet by mutableStateOf(false)
    private var currentSongIndex by mutableStateOf(0)
    private var isPlaying by mutableStateOf(false)
    private lateinit var mediaPlayer: MediaPlayer
    private val pickAudioLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.also { uri ->
                    val floatArray = readWavFile(uri)
                    // 处理 floatArray
                    musicList.add(musicwav("local", 0, copyFromLocal(uri), getFileName(uri)))
                    musicList.add(musicwav("local", 0, "", getFileName(uri) + "_pr"))
                    Thread {
                        predict(floatArray!!)
                        runOnUiThread {
                            Toast.makeText(
                                this,
                                "推理总时长为:${inferenceTimeori / 1000}s",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }.start()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            mediaPlayer = MediaPlayer.create(this, musicList[0].rawId)
            AudioasAndroidTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MusicPlayer(
                        musicList = musicList,
                        currentSongIndex = currentSongIndex,
                        isPlaying = isPlaying,
                        mediaPlayer = mediaPlayer,
                        showBottomSheet = showBottomSheet,
                        onPreviousClick = { playPrevious() },
                        onPlayPauseClick = { togglePlayPause() },
                        onNextClick = { playNext() },
                        onImportClick = { load() },
                        onSongIndexChange = ::onSongIndexChange,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }


    private fun togglePlayPause() {
        if (isPlaying) {
            pauseSong()
        } else {
            mediaPlayer.start()
            isPlaying = true
        }
    }

    private fun onSongIndexChange(newIndex: Int) {
        currentSongIndex = newIndex
        startSong()
    }

    //播放新的歌曲
    private fun startSong() {
        if (::mediaPlayer.isInitialized) {
            mediaPlayer.reset() // 重置MediaPlayer的状态
        } else {
            mediaPlayer = MediaPlayer()
        }
        //判断当前播放歌曲是在手机本地读取的还是预存的
        if (musicList[currentSongIndex].type == "prepare") {
            mediaPlayer = MediaPlayer.create(this, musicList[currentSongIndex].rawId)
            mediaPlayer.start()
            mediaPlayer.setOnCompletionListener {
                isPlaying = false // 播放完成后将 isPlaying 的值设置为 false
            }
            isPlaying = true
        } else {
            //判断推理好了没有
            if (musicList[currentSongIndex].path == "") {
                Toast.makeText(this, "语音正在推理", Toast.LENGTH_SHORT).show()
            } else {
                mediaPlayer = MediaPlayer()
                mediaPlayer.setDataSource(musicList[currentSongIndex].path) // outputPath 是音频文件的路径
                mediaPlayer.prepare()
                mediaPlayer.start()
                mediaPlayer.setOnCompletionListener {
                    isPlaying = false // 播放完成后将 isPlaying 的值设置为 false
                }
                isPlaying = true
            }
        }

    }

    private fun pauseSong() {
        if (::mediaPlayer.isInitialized && mediaPlayer.isPlaying) {
            mediaPlayer.pause()
            isPlaying = false
        }
    }

    private fun playPrevious() {
        if (currentSongIndex > 0) {
            currentSongIndex--
            startSong()
        }
    }

    private fun playNext() {
        if (currentSongIndex < musicList.size - 1) {
            currentSongIndex++
            startSong()
        }
    }

    //通过uri读文件名
    private fun getFileName(uri: Uri): String {
        var name = "Unknown"
        val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    name = it.getString(nameIndex)
                }
            }
        }
        return name
    }

    private fun load() {
        requestPermissionsLauncher.launch(permissions)
    }

    private fun predict(floatArray: FloatArray) {
        val batchsize = 55000
        val n = ceil(floatArray.size / batchsize.toDouble())
        val mutlwave: ArrayList<FloatArray> = ArrayList()
        var start: Int
        var end: Int
        for (i in 0..(n.toInt() - 1)) {
            start = i * batchsize
            end = min((i + 1) * batchsize, floatArray.size)
            mutlwave.add(floatArray.sliceArray(start..(end - 1)))
        }
        //推理！！
        val startTime_ori = System.currentTimeMillis()
        val resultList = mutableListOf<Float>()//存放最终结果

        for (temp in mutlwave) {
            val upsample = predict_onebatch(temp)
            resultList.addAll(upsample.toList())
        }
        val endTime_ori = System.currentTimeMillis()
        inferenceTimeori = endTime_ori - startTime_ori
        val resultArray = resultList.toFloatArray()
        print(resultArray.size)
        //推理完毕，把推理出来的floatArray转回wav格式
        val processedPcmData = floatArrayToPCM(resultArray)
        val wavHeader =
            createWavHeader(processedPcmData.size, sr, numChannels, bitsPerSample)
        var outputPath = File(filesDir, "output.wav").absolutePath
        FileOutputStream(outputPath).use { fos ->
            fos.write(wavHeader)
            fos.write(processedPcmData)
        }
        musicList.lastOrNull()?.path = outputPath

    }

    //推理一个时间batch
    private fun predict_onebatch(floatArray: FloatArray): FloatArray {
        val waveformSize = longArrayOf(1, 1, floatArray.size.toLong())
        val waveformTensor = Tensor.fromBlob(floatArray, waveformSize)
        module_ori = Module.load(assetFilePath(this, "mobile_super_model.pt"))
        val modulepath = assetFilePath(this, "mobile_super_model.pt")
        val upsampleWaveTensor = module_ori.forward(IValue.from(waveformTensor)).toTensor()
        val upsampleWaveFloatArray = upsampleWaveTensor.dataAsFloatArray
        return upsampleWaveFloatArray

    }

    //读取asset文件夹里资源路径
    fun assetFilePath(context: Context, assetName: String): String {
        var file = File(context.filesDir, assetName)
        if (file.exists() && file.length() > 0) {
            return file.absolutePath
        }
        //use用于处理需要关闭的资源
        context.assets.open(assetName).use { inputStream ->
            FileOutputStream(file).use { outputStream ->
                val buffer = ByteArray(4 * 1024)
                var read: Int
                while (inputStream.read(buffer).also { read = it } != -1) {
                    outputStream.write(buffer, 0, read)
                }
                outputStream.flush()
            }
        }
        return file.absolutePath
    }

    //float转PCM
    private fun floatArrayToPCM(floatArray: FloatArray): ByteArray {
        val pcmData = ByteArray(floatArray.size * 2)
        val buffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN)
        for (value in floatArray) {
            buffer.putShort((value * Short.MAX_VALUE).toInt().toShort())
        }
        return pcmData
    }

    //创建wav文件头
    private fun createWavHeader(
        pcmDataLength: Int,
        sampleRate: Int,
        numChannels: Int,
        bitsPerSample: Int
    ): ByteArray {
        val byteRate = sampleRate * numChannels * bitsPerSample / 8
        val blockAlign = numChannels * bitsPerSample / 8
        val subchunk2Size = pcmDataLength
        val chunkSize = 36 + subchunk2Size

        val buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt(chunkSize)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16) // Subchunk1Size for PCM
        buffer.putShort(1.toShort()) // AudioFormat (1 for PCM)
        buffer.putShort(numChannels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(bitsPerSample.toShort())
        buffer.put("data".toByteArray())
        buffer.putInt(subchunk2Size)

        return buffer.array()
    }

    //音频读取许可
    private val requestPermissionsLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            if (permissions.all { it.value }) {
                // 所有权限都已授予
                openAudioPicker()
            } else {
                // 有权限被拒绝
                Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    private fun openAudioPicker() {

        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "audio/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        pickAudioLauncher.launch(Intent.createChooser(intent, "Select an audio file"))
    }

    //读取wav文件并返回一个float数组准备推理
    private fun readWavFile(uri: Uri): FloatArray? {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val wavHeader = ByteArray(44)
                inputStream.read(wavHeader, 0, 44)

                val byteBuffer = ByteBuffer.wrap(wavHeader)
                byteBuffer.order(ByteOrder.LITTLE_ENDIAN)

                val audioFormat = byteBuffer.getShort(20).toInt()
//        val numChannels = byteBuffer.getShort(22).toInt()
//        val sampleRate = byteBuffer.getInt(24)
                val bitsPerSample = byteBuffer.getShort(34).toInt()

                if (audioFormat != 1 || bitsPerSample != 16) {
                    throw UnsupportedOperationException("Only PCM 16-bit WAV files are supported")
                }

                val dataSize = inputStream.available()
                val audioData = ByteArray(dataSize)
                inputStream.read(audioData)
                inputStream.close()

                val floatArray = FloatArray(dataSize / 2)
                val audioBuffer = ByteBuffer.wrap(audioData)
                audioBuffer.order(ByteOrder.LITTLE_ENDIAN)

                for (i in floatArray.indices) {
                    floatArray[i] = audioBuffer.short.toFloat() / Short.MAX_VALUE
                }
                return floatArray
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return null
    }

    private fun copyFromLocal(uri: Uri): String {
        val externalFileUri: Uri = uri// 获取外部文件的 URI

        val inputStream: InputStream? = contentResolver.openInputStream(externalFileUri)
        val outputStream: OutputStream? = FileOutputStream(getFileStreamPath("new_file.wav"))

        if (inputStream != null && outputStream != null) {
            val buffer = ByteArray(1024)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }
        }

        inputStream?.close()
        outputStream?.close()
//        print(getFileStreamPath("new_file.wav").absolutePath)
        return getFileStreamPath("new_file.wav").absolutePath
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicPlayer(
    musicList: MutableList<musicwav>,
    currentSongIndex: Int,
    isPlaying: Boolean,
    mediaPlayer: MediaPlayer,
    showBottomSheet: Boolean,
    onPreviousClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onImportClick: () -> Unit,
    onSongIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState()
    val coroutineScope = rememberCoroutineScope()
    var currentPosition by remember { mutableStateOf(0f) }
    var duration by remember { mutableStateOf(mediaPlayer.duration.toFloat()) }
    //两个协程一个更新进度条，一个更新歌曲长度
    LaunchedEffect(isPlaying, mediaPlayer) {
        while (isPlaying) {
            if (mediaPlayer.isPlaying) {
                currentPosition = mediaPlayer.currentPosition.toFloat()
            }
            delay(50)
        }
    }
    LaunchedEffect(mediaPlayer) {
        duration = mediaPlayer.duration.toFloat()
        currentPosition = 0f
    }

    val infiniteTransition = rememberInfiniteTransition()

    // 定义旋转角度的动画
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    // 如果 isPlaying 为 false，则停止旋转
    val displayedRotation = if (isPlaying) rotation else 0f
    //布局
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Image(
            painter = painterResource(id = R.drawable.background_image),
            contentDescription = "Background Image",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 80.dp), // 给底部控制栏留出空间
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(id = R.drawable.steins_gate_hashida_itaru_makise_kurisu_shiina_mayuri_wallpaper), // 替换为专辑封面资源ID
                contentDescription = "Album Cover",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(250.dp) // 设置专辑封面的大小
                    .clip(CircleShape) // 将专辑封面裁剪为圆形
                    .rotate(displayedRotation)
            )
            Spacer(modifier = Modifier.height(40.dp))
            Text(text = "Now Playing: ${musicList[currentSongIndex].name}")
            Spacer(modifier = Modifier.height(16.dp))
            if (currentSongIndex % 2 == 0) {
                Text(text = "The sample rate of this song is : 12000Hz")
            } else {
                Text(text = "The sample rate of this song is : 48000Hz")
            }
            Spacer(modifier = Modifier.height((100.dp)))
            Slider(
                value = currentPosition,
                onValueChange = { newValue ->
                    currentPosition = newValue
                    if (mediaPlayer != null) {
                        mediaPlayer.seekTo(newValue.toInt())
                    }
                },
                valueRange = 0f..duration,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFF6200EE),
                    activeTrackColor = Color(0xFF6200EE)
                ),
                modifier = Modifier
                    .width(350.dp)
                    .offset(y = 80.dp)

            )
        }

        bottomControlBar(
            isPlaying = isPlaying,
            onPreviousClick = onPreviousClick,
            onPlayPauseClick = onPlayPauseClick,
            onNextClick = onNextClick,
            onImportClick = onImportClick,
            onSongListClick = { coroutineScope.launch { sheetState.show() } },
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter) // 将子组件对齐到Box的底部中心
                .padding(bottom = 80.dp)
        )
        if (sheetState.isVisible) {
            ModalBottomSheet(
                onDismissRequest = { coroutineScope.launch { sheetState.hide() } },
                sheetState = sheetState
            ) {
                SongList(
                    songList = musicList,
                    onSongClick = { song ->
                        // Handle song click
                        println("Playing $song")
                        onSongIndexChange(musicList.indexOf(song))
                        coroutineScope.launch { sheetState.hide() }
                    }
                )
            }
        }


    }
}

//播放列表ui
@Composable
fun SongList(songList: MutableList<musicwav>, onSongClick: (musicwav) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text("播放列表", fontSize = 24.sp, modifier = Modifier.padding(bottom = 12.dp))
        songList.forEach { song ->
            Text(
                text = song.name,
                fontSize = 16.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSongClick(song) }
                    .padding(vertical = 8.dp)
            )
        }
    }
}

//底部控制控件buttonbar
@Composable
fun bottomControlBar(
    isPlaying: Boolean,
    onPreviousClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onImportClick: () -> Unit,
    onSongListClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        importSongButton(onClick = onImportClick)
        previewSongButton(onClick = onPreviousClick)
        playPauseSongButton(isPlaying = isPlaying, onClick = onPlayPauseClick)
        nextSongButton(onClick = onNextClick)
        songListButton(onClick = onSongListClick)
    }
}


//播放暂停button ui
@Composable
fun playPauseSongButton(isPlaying: Boolean, onClick: () -> Unit) {
    print(isPlaying)
    IconButton(onClick = onClick, modifier = Modifier.size(60.dp)) {
        Icon(
            painter = if (isPlaying) {
                painterResource(id = R.drawable.qq_pause_button)
            } else {
                painterResource(id = R.drawable.qq_play_button)
//              ImageVector.vectorResource(id = R.drawable.ic_play_button)
            },
            contentDescription = if (isPlaying) "Pause" else "Play"
        )
    }

}


//上一首歌曲button ui
@Composable
fun previewSongButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            painter = painterResource(id = R.drawable.qq_previous_button),
//          imageVector =ImageVector.vectorResource(id = R.drawable.qq_previous_button),
            contentDescription = "Next"
        )

    }
}


//下一首歌曲button ui
@Composable
fun nextSongButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            painter = painterResource(id = R.drawable.qq_next_button),
//          imageVector = ImageVector.vectorResource(id = R.drawable.ic_next_button),
            contentDescription = "Next"
        )
    }
}


//导入本地歌曲Button ui
@Composable
fun importSongButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            painter = painterResource(id = R.drawable.qq_import_button),
            contentDescription = "Import"
        )
    }
}

//显示歌曲列表Button ui
@Composable
fun songListButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            painter = painterResource(id = R.drawable.qq_list_button),
            contentDescription = "Song list"
        )
    }
}




