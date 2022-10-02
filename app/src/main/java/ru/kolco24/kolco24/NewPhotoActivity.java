package ru.kolco24.kolco24;

import androidx.appcompat.app.AppCompatActivity;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import ru.kolco24.kolco24.data.Photo;
import ru.kolco24.kolco24.ui.photo.PhotoViewModel;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class NewPhotoActivity extends AppCompatActivity {
    public static final String POINT_NAME = "ru.kolco24.kolco24.newPhoto.pointName";
    public static final String PHOTO_URI = "ru.kolco24.kolco24.newPhoto.photoUri";
    static final int REQUEST_IMAGE_GALLERY = 2;
    private EditText mPointNameEditView;

    private int photoId;
    private String photoUri;
    private String photoThumbUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_new_word);
        mPointNameEditView = findViewById(R.id.edit_word);

        Intent callingIntent = getIntent();
        photoId = callingIntent.getIntExtra("id", 0);
        int point_number = callingIntent.getIntExtra("point_number", 0);
        photoUri = callingIntent.getStringExtra("photo_uri");
        photoThumbUri = callingIntent.getStringExtra("photo_thumb_uri");

        if (point_number != 0) {
            Locale locale = getResources().getConfiguration().locale;
            mPointNameEditView.setText(String.format(locale, "%d", point_number));
        }
        if (photoId != 0) {
            Button galleryButton = findViewById(R.id.button_gallery);
            galleryButton.setVisibility(View.GONE);

            ImageView imageView = findViewById(R.id.imageView);
            imageView.setImageURI(Uri.parse(photoUri));
        }

        final Button saveButton = findViewById(R.id.button_save);
        saveButton.setOnClickListener(view -> {
            if (TextUtils.isEmpty(mPointNameEditView.getText())) {
                Toast.makeText(getApplicationContext(), "Укажите номер КП", Toast.LENGTH_SHORT).show();
                return;
            }
            if (photoUri == null) {
                Toast.makeText(getApplicationContext(), "Выберите фото", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!TextUtils.isEmpty(mPointNameEditView.getText()) && photoUri != null) {
                PhotoViewModel photoViewModel = new PhotoViewModel(getApplication());
                if (photoId == 0) {
                    // Create new photo
                    photoViewModel.insert(new Photo(
                            "Team 1",
                            Integer.parseInt(mPointNameEditView.getText().toString()),
                            photoUri,
                            photoThumbUri
                    ));
                } else {
                    // Update photo
                    AsyncTask.execute(() -> {
                        Photo photo = photoViewModel.getPhotoById(photoId);
                        boolean isChanged = false;
                        if (photo.point_number != Integer.parseInt(mPointNameEditView.getText().toString())) {
                            photo.point_number = Integer.parseInt(mPointNameEditView.getText().toString());
                            isChanged = true;
                        }
                        if (!photo.photo_url.equals(photoUri)) {
                            photo.photo_url = photoUri;
                            photo.photo_thumb_url = photoThumbUri;
                            isChanged = true;
                        }
                        if (isChanged) {
                            photo.status = Photo.NEW;
                            photoViewModel.update(photo);
                        } else {
                            runOnUiThread(() -> Toast.makeText(
                                    getApplicationContext(),
                                    "Изменений не было",
                                    Toast.LENGTH_SHORT
                            ).show());

                        }
                    });
                }
            }
            finish();
        });

        final Button cancelButton = findViewById(R.id.button_cancel);
        cancelButton.setOnClickListener(view_cancel -> finish());

        //gallery
        final Button galleryButton = findViewById(R.id.button_gallery);
        galleryButton.setOnClickListener(this::openGallery);

        //editImage
        final ImageView editIcon = findViewById(R.id.edit_icon);
        editIcon.setOnClickListener(this::openGallery);
    }

    private void openGallery(View v) {
        Intent takePictureIntent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.INTERNAL_CONTENT_URI);
        try {
            startActivityForResult(takePictureIntent, REQUEST_IMAGE_GALLERY);
        } catch (ActivityNotFoundException e) {
            // display error state to the user
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IMAGE_GALLERY && resultCode == RESULT_OK) {
            Uri imageUri = data.getData();

            Bitmap bitmapMain = null;
            Bitmap bitmapThumb = null;
            try {
                Bitmap bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), imageUri);
                bitmap = cropBitmap(bitmap);
                bitmapMain = Bitmap.createScaledBitmap(bitmap, 1200, 1200, false);
                bitmapThumb = Bitmap.createScaledBitmap(bitmap, 300, 300, false);
                bitmap.recycle();

                String imageName = generateImageName();
                photoUri = save_image(bitmapMain, imageName + ".jpg");
                photoThumbUri = save_image(bitmapThumb, imageName + "_thumb.jpg");
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }

            ImageView image = findViewById(R.id.imageView);
            image.setImageURI(Uri.parse(photoUri));

            Button galleryButton = findViewById(R.id.button_gallery);
            galleryButton.setVisibility(View.GONE);
        }
    }

    public String save_image(Bitmap bitmap, String name) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 50, bytes);

        File photoFile = createImageFile(name);
        photoFile.createNewFile();

        FileOutputStream fo = new FileOutputStream(photoFile);
        fo.write(bytes.toByteArray());
        MediaScannerConnection.scanFile(this,
                new String[]{photoFile.getPath()},
                new String[]{"image/jpeg"}, null);
        fo.close();
        return photoFile.toString();
    }

    public File createImageFile(String name) {
        File picDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (!picDir.exists()) {
            picDir.mkdirs();
        }
        return new File(picDir, name);
    }

    public String generateImageName() {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        return "img_" + timeStamp;
    }

    public static Bitmap cropBitmap(Bitmap srcBmp) {
        Bitmap dstBmp;
        if (srcBmp.getWidth() >= srcBmp.getHeight()) {
            dstBmp = Bitmap.createBitmap(
                    srcBmp,
                    srcBmp.getWidth() / 2 - srcBmp.getHeight() / 2,
                    0,
                    srcBmp.getHeight(),
                    srcBmp.getHeight()
            );

        } else {
            dstBmp = Bitmap.createBitmap(
                    srcBmp,
                    0,
                    srcBmp.getHeight() / 2 - srcBmp.getWidth() / 2,
                    srcBmp.getWidth(),
                    srcBmp.getWidth()
            );
        }
        return dstBmp;
    }

}