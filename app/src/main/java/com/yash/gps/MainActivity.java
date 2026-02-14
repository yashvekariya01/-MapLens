package com.yash.gps;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.media.ExifInterface;
import android.media.ThumbnailUtils;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "GPSMapCamera";
    private static final int REQUEST_CODE_PERMISSIONS = 101;
    private static final String[] REQUIRED_PERMISSIONS = new String[]{
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO
    };

    private PreviewView viewFinder;
    private TextView tvLatLong, tvDateTime, tvAddress, tvGpsStatus, tvVideoTimer;
    private ImageButton btnCapture, btnSwitchCamera, btnFlash, btnGrid;
    private TextView modePhoto, modeVideo;
    private ImageView ivGalleryThumb;
    private CardView cardGallery, cardGpsStatus;
    private View shutterFlash, gridOverlay;

    private ProcessCameraProvider cameraProvider;
    private ImageCapture imageCapture;
    private VideoCapture<Recorder> videoCapture;
    private Recording recording = null;
    private Camera camera = null;

    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    private int flashMode = ImageCapture.FLASH_MODE_OFF;
    private boolean isGridVisible = false;
    private boolean isVideoMode = false;
    private boolean isSwitchingCameraDuringRecording = false;

    private ExecutorService cameraExecutor;
    private Handler timerHandler = new Handler(Looper.getMainLooper());
    private long startTime = 0L;

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private String currentAddr = "Fetching location...";
    private Location currentLocation = null;
    private Uri lastSavedUri = null;
    private long lastAddressUpdate = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Bind UI Elements
        viewFinder = findViewById(R.id.viewFinder);
        tvLatLong = findViewById(R.id.tv_lat_long);
        tvDateTime = findViewById(R.id.tv_date_time);
        tvAddress = findViewById(R.id.tv_address);
        tvGpsStatus = findViewById(R.id.tv_gps_status);
        tvVideoTimer = findViewById(R.id.tv_video_timer);
        btnCapture = findViewById(R.id.btn_capture);
        btnSwitchCamera = findViewById(R.id.btn_switch_camera);
        btnFlash = findViewById(R.id.btn_flash);
        btnGrid = findViewById(R.id.btn_grid);
        modePhoto = findViewById(R.id.mode_photo);
        modeVideo = findViewById(R.id.mode_video);
        ivGalleryThumb = findViewById(R.id.iv_gallery_thumb);
        cardGallery = findViewById(R.id.card_gallery);
        cardGpsStatus = findViewById(R.id.card_gps_status);
        shutterFlash = findViewById(R.id.shutter_flash);
        gridOverlay = findViewById(R.id.grid_overlay);

        cameraExecutor = Executors.newFixedThreadPool(2);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        if (allPermissionsGranted()) {
            setupCamera();
            startLocationUpdates();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }

        setupClickListeners();
        registerNetworkCallback();
    }

    private void setupClickListeners() {
        btnCapture.setOnClickListener(v -> {
            if (isVideoMode) {
                if (recording != null) stopRecording(); else startRecording();
            } else {
                takePhoto();
            }
        });

        btnSwitchCamera.setOnClickListener(v -> {
            if (recording != null) return;
            lensFacing = (lensFacing == CameraSelector.LENS_FACING_BACK) ?
                    CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
            startCamera();
        });

        btnFlash.setOnClickListener(v -> toggleFlash());
        btnGrid.setOnClickListener(v -> toggleGrid());
        modePhoto.setOnClickListener(v -> setMode(false));
        modeVideo.setOnClickListener(v -> setMode(true));

        cardGallery.setOnClickListener(v -> {
            if (lastSavedUri != null) {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(lastSavedUri, isVideoMode ? "video/*" : "image/*");
                intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(intent);
            } else {
                Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                try { startActivity(intent); } catch (Exception e) { Toast.makeText(this, "Gallery not found", Toast.LENGTH_SHORT).show(); }
            }
        });
    }

    private void setMode(boolean video) {
        if (recording != null) return;
        if (isVideoMode == video) return;
        isVideoMode = video;
        modePhoto.setTextColor(video ? Color.WHITE : Color.parseColor("#FFC107"));
        modeVideo.setTextColor(video ? Color.parseColor("#FFC107") : Color.WHITE);
        startCamera();
    }

    private void setupCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                startCamera();
            } catch (Exception e) { Log.e(TAG, "Camera fail", e); }
        }, ContextCompat.getMainExecutor(this));
    }

    private void startCamera() {
        if (cameraProvider == null) return;
        
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(viewFinder.getSurfaceProvider());
        CameraSelector cameraSelector = new CameraSelector.Builder().requireLensFacing(lensFacing).build();
        
        cameraProvider.unbindAll();
        
        if (isVideoMode) {
            Recorder recorder = new Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD)).build();
            videoCapture = VideoCapture.withOutput(recorder);
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, videoCapture);
        } else {
            imageCapture = new ImageCapture.Builder().setFlashMode(flashMode).build();
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);
        }
    }

    private void startRecording() {
        if (videoCapture == null) return;
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "GPS_V_" + System.currentTimeMillis());
        values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/GPSMapCamera");
        }
        
        // Burn location into Metadata - Permanent Burn for Gallery Info
        if (currentLocation != null) {
            values.put(MediaStore.Video.Media.LATITUDE, currentLocation.getLatitude());
            values.put(MediaStore.Video.Media.LONGITUDE, currentLocation.getLongitude());
            values.put(MediaStore.Video.Media.DESCRIPTION, "GPS: " + currentAddr + " | " + tvDateTime.getText());
        }
        
        MediaStoreOutputOptions options = new MediaStoreOutputOptions.Builder(getContentResolver(), MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(values).build();
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        
        recording = videoCapture.getOutput().prepareRecording(this, options)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(this), recordEvent -> {
            if (recordEvent instanceof VideoRecordEvent.Start) {
                runOnUiThread(() -> {
                    tvVideoTimer.setVisibility(View.VISIBLE);
                    startTime = System.currentTimeMillis();
                    timerHandler.postDelayed(timerRunnable, 0);
                    btnCapture.setImageResource(android.R.drawable.presence_video_busy);
                });
            } else if (recordEvent instanceof VideoRecordEvent.Finalize) {
                runOnUiThread(() -> {
                    tvVideoTimer.setVisibility(View.GONE);
                    timerHandler.removeCallbacks(timerRunnable);
                    btnCapture.setImageResource(R.drawable.camera_logo);
                    VideoRecordEvent.Finalize finalizeEvent = (VideoRecordEvent.Finalize) recordEvent;
                    if (!finalizeEvent.hasError()) {
                        lastSavedUri = finalizeEvent.getOutputResults().getOutputUri();
                        updateGalleryThumb(null, lastSavedUri);
                        Toast.makeText(MainActivity.this, "Video Saved with Location Info!", Toast.LENGTH_SHORT).show();
                    }
                });
                recording = null;
            }
        });
    }

    private void stopRecording() { if (recording != null) { recording.stop(); recording = null; } }

    private void startLocationUpdates() {
        LocationRequest request = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000).build();
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                Location loc = result.getLastLocation();
                if (loc != null) {
                    currentLocation = loc;
                    runOnUiThread(() -> {
                        tvLatLong.setText(String.format(Locale.getDefault(), "Lat: %.6f, Long: %.6f", loc.getLatitude(), loc.getLongitude()));
                        tvDateTime.setText(new SimpleDateFormat("EEEE, MMM d, yyyy HH:mm:ss", Locale.getDefault()).format(new Date()));
                    });
                    if (System.currentTimeMillis() - lastAddressUpdate > 15000) fetchAddress(loc);
                }
            }
        };
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper());
        }
    }

    private void registerNetworkCallback() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkRequest request = new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
        cm.registerNetworkCallback(request, new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(@NonNull Network n) { runOnUiThread(() -> { if(tvGpsStatus != null) { tvGpsStatus.setText("GPS ACTIVE"); tvGpsStatus.setTextColor(Color.GREEN); } }); }
            @Override public void onLost(@NonNull Network n) { runOnUiThread(() -> { if(tvGpsStatus != null) { tvGpsStatus.setText("NO INTERNET / GPS OFF"); tvGpsStatus.setTextColor(Color.RED); } }); }
        });
    }

    private void fetchAddress(Location loc) {
        cameraExecutor.execute(() -> {
            try {
                List<Address> addresses = new Geocoder(this, Locale.getDefault()).getFromLocation(loc.getLatitude(), loc.getLongitude(), 1);
                if (addresses != null && !addresses.isEmpty()) {
                    currentAddr = addresses.get(0).getAddressLine(0);
                    lastAddressUpdate = System.currentTimeMillis();
                    runOnUiThread(() -> tvAddress.setText(currentAddr));
                }
            } catch (Exception e) { Log.e(TAG, "Geo error", e); }
        });
    }

    private void takePhoto() {
        if (imageCapture == null) return;
        runOnUiThread(() -> {
            shutterFlash.setVisibility(View.VISIBLE);
            shutterFlash.animate().alpha(0f).setDuration(150).setListener(new AnimatorListenerAdapter() { @Override public void onAnimationEnd(Animator a) { shutterFlash.setVisibility(View.GONE); shutterFlash.setAlpha(1f); } });
        });
        File photoFile = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), System.currentTimeMillis() + ".jpg");
        imageCapture.takePicture(new ImageCapture.OutputFileOptions.Builder(photoFile).build(), cameraExecutor, new ImageCapture.OnImageSavedCallback() {
            @Override public void onImageSaved(@NonNull ImageCapture.OutputFileResults res) { processAndSave(photoFile); }
            @Override public void onError(@NonNull ImageCaptureException e) { Log.e(TAG, "Capture failed", e); }
        });
    }

    private void processAndSave(File photoFile) {
        Bitmap bitmap = BitmapFactory.decodeFile(photoFile.getAbsolutePath());
        if (bitmap == null) return;
        bitmap = handleRotation(photoFile, bitmap);
        Bitmap mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(mutable);
        int w = mutable.getWidth(), h = mutable.getHeight();
        Paint p = new Paint(); p.setColor(Color.parseColor("#99000000"));
        int margin = w/20, fontSize = w/30;
        String txt = tvLatLong.getText() + "\n" + tvDateTime.getText() + "\n" + currentAddr;
        canvas.drawRect(0, h-(fontSize*6), w, h, p);
        p.setColor(Color.WHITE); p.setTextSize(fontSize); p.setAntiAlias(true);
        float x = margin, y = h - (fontSize * 4);
        for (String line : txt.split("\n")) { canvas.drawText(line, x, y, p); y += fontSize + 10; }
        saveImage(mutable);
        photoFile.delete(); // Fixed compilation error
    }

    private void saveImage(Bitmap bitmap) {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, "GPS_" + System.currentTimeMillis());
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GPSMapCamera");
        }
        if (currentLocation != null) {
            v.put(MediaStore.Images.Media.LATITUDE, currentLocation.getLatitude());
            v.put(MediaStore.Images.Media.LONGITUDE, currentLocation.getLongitude());
        }
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out);
            updateGalleryThumb(bitmap, uri);
        } catch (Exception e) { Log.e(TAG, "Save failed", e); }
    }

    private void updateGalleryThumb(Bitmap bitmap, Uri uri) {
        runOnUiThread(() -> {
            lastSavedUri = uri;
            if (bitmap != null) {
                ivGalleryThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                ivGalleryThumb.setImageBitmap(ThumbnailUtils.extractThumbnail(bitmap, 150, 150));
            } else {
                ivGalleryThumb.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                ivGalleryThumb.setImageResource(R.drawable.outline_gallery_thumbnail_24);
            }
        });
    }

    private Bitmap handleRotation(File f, Bitmap b) {
        try {
            int o = new ExifInterface(f.getAbsolutePath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
            Matrix m = new Matrix();
            if (o == 6) m.postRotate(90); else if (o == 3) m.postRotate(180); else if (o == 8) m.postRotate(270);
            else return b;
            return Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        } catch (Exception e) { return b; }
    }

    private boolean allPermissionsGranted() { for (String p : REQUIRED_PERMISSIONS) if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) return false; return true; }
    
    private void toggleFlash() {
        flashMode = (flashMode == ImageCapture.FLASH_MODE_OFF) ? ImageCapture.FLASH_MODE_ON : ImageCapture.FLASH_MODE_OFF;
        btnFlash.setImageResource(flashMode == ImageCapture.FLASH_MODE_ON ? R.drawable.baseline_flash_on_24 : R.drawable.outline_flash_off_24);
        btnFlash.setColorFilter(flashMode == ImageCapture.FLASH_MODE_ON ? Color.YELLOW : Color.WHITE);
        if (camera != null) {
            camera.getCameraControl().enableTorch(flashMode == ImageCapture.FLASH_MODE_ON);
        }
    }

    private void toggleGrid() {
        isGridVisible = !isGridVisible;
        gridOverlay.setVisibility(isGridVisible ? View.VISIBLE : View.GONE);
        btnGrid.setColorFilter(isGridVisible ? Color.YELLOW : Color.WHITE);
    }

    private Runnable timerRunnable = new Runnable() { @Override public void run() { long s = (System.currentTimeMillis() - startTime) / 1000; tvVideoTimer.setText(String.format(Locale.getDefault(), "%02d:%02d:%02d", s/3600, (s%3600)/60, s%60)); timerHandler.postDelayed(this, 1000); } };
    @Override protected void onDestroy() { super.onDestroy(); cameraExecutor.shutdown(); if (fusedLocationClient != null && locationCallback != null) fusedLocationClient.removeLocationUpdates(locationCallback); }
}