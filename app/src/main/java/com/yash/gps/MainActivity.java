package com.yash.gps;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.ContentValues;
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
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
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
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "GPSMapCamera";
    private static final int REQUEST_CODE_PERMISSIONS = 101;
    private static final String[] REQUIRED_PERMISSIONS = new String[]{
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
    };

    private PreviewView viewFinder;
    private TextView tvLatLong, tvDateTime, tvAddress;
    private ImageButton btnCapture, btnSwitchCamera;
    private ImageView ivGalleryThumb;
    private CardView cardGallery;
    private View shutterFlash;

    private ImageCapture imageCapture;
    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    private ExecutorService cameraExecutor;

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private String currentAddr = "Fetching location...";
    private Uri lastSavedUri = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        viewFinder = findViewById(R.id.viewFinder);
        tvLatLong = findViewById(R.id.tv_lat_long);
        tvDateTime = findViewById(R.id.tv_date_time);
        tvAddress = findViewById(R.id.tv_address);
        btnCapture = findViewById(R.id.btn_capture);
        btnSwitchCamera = findViewById(R.id.btn_switch_camera);
        ivGalleryThumb = findViewById(R.id.iv_gallery_thumb);
        cardGallery = findViewById(R.id.card_gallery);
        shutterFlash = findViewById(R.id.shutter_flash);

        cameraExecutor = Executors.newSingleThreadExecutor();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        if (allPermissionsGranted()) {
            startCamera();
            startLocationUpdates();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }

        btnCapture.setOnClickListener(v -> takePhoto());
        
        btnSwitchCamera.setOnClickListener(v -> {
            lensFacing = (lensFacing == CameraSelector.LENS_FACING_BACK) ?
                    CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
            startCamera();
        });

        cardGallery.setOnClickListener(v -> {
            if (lastSavedUri != null) {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(lastSavedUri, "image/*");
                intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(intent);
            } else {
                Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                startActivity(intent);
            }
        });
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();

                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(lensFacing)
                        .build();

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Use case binding failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void startLocationUpdates() {
        LocationRequest request = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
                .setMinUpdateIntervalMillis(2000)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                Location loc = result.getLastLocation();
                if (loc != null) {
                    String latLong = String.format(Locale.getDefault(), "Lat: %.6f, Long: %.6f", loc.getLatitude(), loc.getLongitude());
                    tvLatLong.setText(latLong);
                    tvDateTime.setText(new SimpleDateFormat("EEEE, MMM d, yyyy HH:mm:ss", Locale.getDefault()).format(new Date()));
                    fetchAddress(loc);
                }
            }
        };

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, null);
        }
    }

    private void fetchAddress(Location loc) {
        Geocoder geocoder = new Geocoder(this, Locale.getDefault());
        try {
            List<Address> addresses = geocoder.getFromLocation(loc.getLatitude(), loc.getLongitude(), 1);
            if (addresses != null && !addresses.isEmpty()) {
                currentAddr = addresses.get(0).getAddressLine(0);
                runOnUiThread(() -> tvAddress.setText(currentAddr));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void takePhoto() {
        if (imageCapture == null) return;

        // Effect: Flash Shutter
        shutterFlash.setVisibility(View.VISIBLE);
        shutterFlash.setAlpha(1f);
        shutterFlash.animate().alpha(0f).setDuration(150).setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                shutterFlash.setVisibility(View.GONE);
            }
        });

        File photoFile = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                System.currentTimeMillis() + ".jpg");

        ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                processAndSave(photoFile);
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                Log.e(TAG, "Photo capture failed", exception);
            }
        });
    }

    private void processAndSave(File photoFile) {
        Bitmap bitmap = BitmapFactory.decodeFile(photoFile.getAbsolutePath());
        if (bitmap == null) return;

        bitmap = handleRotation(photoFile, bitmap);

        Bitmap mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(mutableBitmap);

        int width = mutableBitmap.getWidth();
        int height = mutableBitmap.getHeight();

        int margin = width / 20;
        int padding = width / 40;
        int fontSizeLarge = width / 22;
        int fontSizeSmall = width / 32;
        int lineSpacing = fontSizeSmall / 3;

        String line1 = tvLatLong.getText().toString();
        String line2 = tvDateTime.getText().toString();
        String line3 = "Address: " + currentAddr;

        List<String> addressLines = wrapText(line3, width - (margin * 2) - (padding * 2), fontSizeSmall);
        
        int totalTextLines = 2 + addressLines.size(); 
        int totalContentHeight = fontSizeLarge + (totalTextLines - 1) * fontSizeSmall + (totalTextLines * lineSpacing) + (padding * 2);
        
        Paint bgPaint = new Paint();
        bgPaint.setColor(Color.parseColor("#AA000000")); 
        bgPaint.setStyle(Paint.Style.FILL);
        
        Rect bgRect = new Rect(margin, height - totalContentHeight - margin, width - margin, height - margin);
        canvas.drawRect(bgRect, bgPaint);

        Paint textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setAntiAlias(true);
        
        float x = bgRect.left + padding;
        float y = bgRect.top + padding + fontSizeLarge;

        textPaint.setTextSize(fontSizeLarge);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        canvas.drawText(line1, x, y, textPaint);

        y += fontSizeSmall + lineSpacing;
        textPaint.setTextSize(fontSizeSmall);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
        canvas.drawText(line2, x, y, textPaint);

        for (String addrLine : addressLines) {
            y += fontSizeSmall + lineSpacing;
            canvas.drawText(addrLine, x, y, textPaint);
        }

        saveImageToGallery(mutableBitmap);
        photoFile.delete();
    }

    private Bitmap handleRotation(File file, Bitmap bitmap) {
        try {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED);
            Matrix matrix = new Matrix();
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    matrix.postRotate(90);
                    break;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    matrix.postRotate(180);
                    break;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    matrix.postRotate(270);
                    break;
                default:
                    return bitmap;
            }
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return bitmap;
    }

    private List<String> wrapText(String text, float maxWidth, float fontSize) {
        List<String> lines = new ArrayList<>();
        Paint paint = new Paint();
        paint.setTextSize(fontSize);
        String[] words = text.split(" ");
        StringBuilder currentLine = new StringBuilder();
        for (String word : words) {
            if (paint.measureText(currentLine + " " + word) <= maxWidth) {
                if (currentLine.length() > 0) currentLine.append(" ");
                currentLine.append(word);
            } else {
                lines.add(currentLine.toString());
                currentLine = new StringBuilder(word);
            }
        }
        if (currentLine.length() > 0) lines.add(currentLine.toString());
        return lines;
    }

    private void saveImageToGallery(Bitmap bitmap) {
        String fileName = "GPS_" + System.currentTimeMillis() + ".jpg";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GPSMapCamera");
        }
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out);
            lastSavedUri = uri;
            runOnUiThread(() -> {
                // Update Thumbnail with Animation
                ivGalleryThumb.setImageBitmap(bitmap);
                ivGalleryThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                cardGallery.animate().scaleX(1.2f).scaleY(1.2f).setDuration(100).withEndAction(() -> 
                        cardGallery.animate().scaleX(1f).scaleY(1f).setDuration(100));
                
                Toast.makeText(this, "Photo Saved!", Toast.LENGTH_SHORT).show();
            });
        } catch (Exception e) {
            Log.e(TAG, "Save failed", e);
        }
    }

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS && allPermissionsGranted()) {
            startCamera();
            startLocationUpdates();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
    }
}