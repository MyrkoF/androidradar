package ch.lab77.radar.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lab77.radar.data.Device
import ch.lab77.radar.data.RangeFinder
import ch.lab77.radar.scan.SystemTweaks
import ch.lab77.radar.data.ScanRepository
import ch.lab77.radar.scan.ArTracker
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Écran « Suivi caméra » (ARCore, #16) : l'image de la caméra en fond, l'état du suivi, la distance parcourue.
 * Tant qu'il est ouvert, la position du téléphone est celle d'ARCore. « Pointer l'objet » (si un objet est
 * sélectionné) : viser le boîtier avec le réticule → position exacte enregistrée.
 */
@Composable
fun ArScreen(target: Device?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    val tracker = remember { ArTracker(ctx) }
    var camGranted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { camGranted = it }
    var ready by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(tracker.availability()) }
    var pointed by remember { mutableStateOf<String?>(null) }
    var wantHit by remember { mutableStateOf(false) }
    val st by ScanRepository.status.collectAsStateWithLifecycle()
    val main = remember { Handler(Looper.getMainLooper()) }

    LaunchedEffect(camGranted) {
        if (!camGranted) { ask.launch(Manifest.permission.CAMERA); return@LaunchedEffect }
        val installed = activity?.let { tracker.ensureInstalled(it) } ?: false
        info = tracker.availability()
        if (installed && tracker.create()) { tracker.resume(); ready = true }
    }
    DisposableEffect(Unit) { onDispose { tracker.pause(); tracker.close() } }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Palette.bg)) {
            if (ready) AndroidView(
                factory = { c ->
                    GLSurfaceView(c).apply {
                        setEGLContextClientVersion(2)
                        preserveEGLContextOnPause = true
                        setRenderer(ArRenderer(tracker, { frame, w, h ->
                            if (wantHit && target != null) {
                                wantHit = false
                                tracker.hitCenter(frame, w, h)?.let { (lat, lon, dist) ->
                                    ScanRepository.pinPosition(target.id, lat, lon, 1.5f)
                                    // (#20) contrôle croisé avec la visée par inclinaison
                                    val tilt = ScanRepository.status.value.pitch?.let { RangeFinder.distanceM(SystemTweaks.eyeHeightCm(ctx) / 100f, it) }
                                    val check = if (tilt != null && kotlin.math.abs(tilt - dist) / maxOf(tilt, dist) > 0.3f)
                                        "\n⚠ visée par inclinaison ${"%.1f".format(tilt)} m ≠ caméra ${"%.1f".format(dist)} m : hauteur des yeux fausse ou pied de l'objet non visé" else ""
                                    main.post { pointed = "✓ ${target.name.ifBlank { target.id }} pointé à ${"%.1f".format(dist)} m (caméra) — position figée$check" }
                                } ?: main.post { pointed = "Rien de solide sous le réticule : visez une surface (mur, boîtier)" }
                            }
                        }))
                        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val c = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2)
                drawCircle(Palette.green, 20.dp.toPx(), c, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            }
            Column(Modifier.fillMaxWidth().align(Alignment.TopCenter).background(Palette.surface.copy(alpha = 0.85f)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Suivi caméra (ARCore) : ${st.arTracking.ifBlank { info }}", color = if (st.arTracking.startsWith("suivi")) Palette.green else Palette.amber, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Text(
                    if (!ready) "Prérequis : ${arState(ctx).label}. Sans ARCore, cet écran ne sert à rien — le reste de l'app marche (voir Session → prérequis)."
                    else "Gardez l'écran ouvert en marchant : votre position suit vos vrais déplacements (±1,5 m). Immobile = position figée.",
                    color = Palette.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp
                )
                tracker.eyeHeightCalibratedCm?.let { Text("Hauteur des yeux calibrée par la caméra : $it cm", color = Palette.green, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                if (target != null) Text("Objet à pointer : ${target.name.ifBlank { target.vendor.ifBlank { target.id } }} — mettez son boîtier sous le réticule", color = Palette.text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                PaceIndicator(st, target?.kind, ar = true)
                pointed?.let { Text(it, color = Palette.green, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            }
            Row(Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(Palette.surface.copy(alpha = 0.85f)).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (target != null) Button(enabled = ready && st.arTracking.startsWith("suivi"), onClick = { wantHit = true }) { Text("Pointer l'objet") }
                OutlinedButton(onClick = onClose) { Text("Fermer") }
            }
        }
    }
}

/** Rendu minimal : l'image caméra ARCore sur un quad plein écran, puis mise à jour du suivi. */
private class ArRenderer(private val tracker: ArTracker, private val onFrame: (Frame, Int, Int) -> Unit) : GLSurfaceView.Renderer {
    private var tex = 0; private var program = 0
    private var w = 0; private var h = 0
    private val quad: FloatBuffer = fb(floatArrayOf(-1f, -1f, -1f, 1f, 1f, -1f, 1f, 1f))
    private val texCoords: FloatBuffer = fb(FloatArray(8))
    private var geometryDirty = true

    private fun fb(a: FloatArray): FloatBuffer = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0); tex = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        val vs = shader(GLES20.GL_VERTEX_SHADER, "attribute vec4 a_Position; attribute vec2 a_TexCoord; varying vec2 v_TexCoord; void main(){ gl_Position = a_Position; v_TexCoord = a_TexCoord; }")
        val fs = shader(GLES20.GL_FRAGMENT_SHADER, "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 v_TexCoord; uniform samplerExternalOES sTexture; void main(){ gl_FragColor = texture2D(sTexture, v_TexCoord); }")
        program = GLES20.glCreateProgram(); GLES20.glAttachShader(program, vs); GLES20.glAttachShader(program, fs); GLES20.glLinkProgram(program)
        tracker.session?.setCameraTextureName(tex)
    }

    private fun shader(type: Int, src: String): Int = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, src); GLES20.glCompileShader(it) }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        w = width; h = height
        GLES20.glViewport(0, 0, width, height)
        tracker.session?.setDisplayGeometry(0, width, height)
        geometryDirty = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val session = tracker.session ?: return
        val frame = try { session.setCameraTextureName(tex); session.update() } catch (_: Exception) { return }
        if (frame.hasDisplayGeometryChanged() || geometryDirty) {
            frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quad, Coordinates2d.TEXTURE_NORMALIZED, texCoords)
            geometryDirty = false
        }
        if (frame.timestamp != 0L) {
            GLES20.glDisable(GLES20.GL_DEPTH_TEST); GLES20.glDepthMask(false)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
            val aPos = GLES20.glGetAttribLocation(program, "a_Position"); val aTex = GLES20.glGetAttribLocation(program, "a_TexCoord")
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad); GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, texCoords); GLES20.glEnableVertexAttribArray(aTex)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPos); GLES20.glDisableVertexAttribArray(aTex)
            GLES20.glDepthMask(true); GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        }
        tracker.onFrame(frame)
        onFrame(frame, w, h)
    }
}
