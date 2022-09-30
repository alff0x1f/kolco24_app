package org.kolco24.kolco24;

import androidx.appcompat.app.AppCompatActivity;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;

import org.kolco24.kolco24.data.Photo;
import org.kolco24.kolco24.ui.photo.PhotoPointViewModel;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class NewPhotoActivity extends AppCompatActivity {
    public static final String POINT_NAME = "org.kolco24.kolco24.newPhoto.pointName";
    public static final String PHOTO_URI = "org.kolco24.kolco24.newPhoto.photoUri";
    static final int REQUEST_IMAGE_GALLERY = 2;
    private EditText mPointNameEditView;
    private String imageName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_new_word);
        mPointNameEditView = findViewById(R.id.edit_word);

        Intent callingIntent = getIntent();
        String pointNumber = callingIntent.getStringExtra("point_number");
        if (pointNumber != null) {
            mPointNameEditView.setText(pointNumber);
        }
        imageName = callingIntent.getStringExtra("uri");
        if (imageName != null) {
            Uri imageUri = Uri.parse(imageName);
            ImageView imageView = findViewById(R.id.imageView);
            imageView.setImageURI(imageUri);
        }

        final Button saveButton = findViewById(R.id.button_save);
        saveButton.setOnClickListener(view -> {
            if (!TextUtils.isEmpty(mPointNameEditView.getText()) && imageName != null) {
                Photo photo = new Photo(
                        1,
                        imageName,
                        mPointNameEditView.getText().toString()
                );
                PhotoPointViewModel a = new PhotoPointViewModel(getApplication());
                a.insert(photo);
            }
            finish();
        });

        //gallery
        final Button galleryButton = findViewById(R.id.button_gallery);
        galleryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dispatchTakePictureIntent();
            }
        });
    }

    private void dispatchTakePictureIntent() {
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

            Bitmap bitmap = null;
            try {
                bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), imageUri);
                bitmap = cropBitmap(bitmap);
                bitmap = Bitmap.createScaledBitmap(bitmap, 300, 300, false);
            } catch (IOException e) {
                e.printStackTrace();
            }
            try {
                imageName = save_image(bitmap);
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }

            ImageView image = findViewById(R.id.imageView);
            image.setImageURI(Uri.parse(imageName));

            Button galleryButton = findViewById(R.id.button_gallery);
            galleryButton.setText("Выбрать другое фото");
        }
    }

    public String save_image(Bitmap bitmap) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 50, bytes);

        File photoFile = createImageFile();
        photoFile.createNewFile();

        FileOutputStream fo = new FileOutputStream(photoFile);
        fo.write(bytes.toByteArray());
        MediaScannerConnection.scanFile(this,
                new String[]{photoFile.getPath()},
                new String[]{"image/jpeg"}, null);
        fo.close();
        return photoFile.toString();
    }

    public File createImageFile() {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String imageFileName = "img_" + timeStamp + ".png";
        File picDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (!picDir.exists()) {
            picDir.mkdirs();
        }
        return new File(picDir, imageFileName);
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