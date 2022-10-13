package ru.kolco24.kolco24.ui.photo;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.ColorDrawable;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import ru.kolco24.kolco24.R;
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
    static final int REQUEST_IMAGE_GALLERY = 1;
    static final int REQUEST_IMAGE_CAPTURE = 2;
    private EditText mPointNameEditView;
    private EditText pointNumberEditField;
    private Uri cameraPhotoUri;

    private int photoId;
    private String photoUri;
    private String photoThumbUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final ActionBar actionBar = getSupportActionBar();
        actionBar.setBackgroundDrawable(
                new ColorDrawable(getResources().getColor(R.color.black))
        );

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
            ImageView imageView = findViewById(R.id.imageView);
            imageView.setImageURI(Uri.parse(photoUri));
        }

        //header action bar
        if (photoId != 0) {
            actionBar.setTitle("Редактирование фото КП");
        } else {
            actionBar.setTitle("Новое фото");
        }

        final Button saveButton = findViewById(R.id.button_save);
        saveButton.setOnClickListener(view -> {
            if (TextUtils.isEmpty(mPointNameEditView.getText())) {
                requestNumber();
//                Toast.makeText(getApplicationContext(), "Укажите номер КП", Toast.LENGTH_SHORT).show();
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
                    int teamId = getApplicationContext().getSharedPreferences(
                            "team",
                            Context.MODE_PRIVATE
                    ).getInt("team_id", 0);
                    photoViewModel.insert(new Photo(
                            teamId,
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

        //editImage
        final ImageView editIcon = findViewById(R.id.edit_icon);
        editIcon.setOnClickListener(this::openGallery);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.photo_menu, menu);
        return true;
    }

    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_camera) {
            openCamera(null);
            return true;
        }
        if (item.getItemId() == R.id.action_gallery) {
            openGallery(null);
            return true;
        }
        if (item.getItemId() == R.id.action_change_number) {
            requestNumber();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void requestNumber() {
        pointNumberEditField = new EditText(this);
        pointNumberEditField.setInputType(2);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Какой номер КП?")
                .setView(pointNumberEditField)
                .setPositiveButton("Ок", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        String editTextInput = pointNumberEditField.getText().toString();
                        mPointNameEditView.setText(editTextInput);
                        final Button saveButton = findViewById(R.id.button_save);
                        saveButton.callOnClick();
                    }
                })
                .setNegativeButton("Отмена", null)
                .create();
        dialog.show();
    }

    private void openGallery(View v) {
        Intent takePictureIntent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.INTERNAL_CONTENT_URI);
        try {
            startActivityForResult(takePictureIntent, REQUEST_IMAGE_GALLERY);
        } catch (ActivityNotFoundException e) {
            // display error state to the user
        }
    }

    private void openCamera(View v) {
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
            String imageName = generateImageName();
            File photoFile = createImageFile(imageName + "_orig.jpg");
            if (photoFile != null) {
                cameraPhotoUri = FileProvider.getUriForFile(this,
                        "ru.kolco24.kolco24.fileprovider",
                        photoFile);
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri);
                startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE);
            }

        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IMAGE_GALLERY && resultCode == RESULT_OK) {
            Uri imageUri = data.getData();

            try {
                Bitmap bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), imageUri);
                Bitmap bitmapMain = scaleBitmap(bitmap, 1200);

                bitmap = cropBitmap(bitmap);
                Bitmap bitmapThumb = Bitmap.createScaledBitmap(bitmap, 300, 300, false);
                bitmap.recycle();

                String imageName = generateImageName();
                photoUri = save_image(bitmapMain, imageName + ".jpg");
                bitmapMain.recycle();
                photoThumbUri = save_image(bitmapThumb, imageName + "_thumb.jpg");
                bitmapThumb.recycle();
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }

            ImageView image = findViewById(R.id.imageView);
            image.setImageURI(Uri.parse(photoUri));
        }

        if (requestCode == REQUEST_IMAGE_CAPTURE && resultCode == RESULT_OK) {
            try {
                Bitmap bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), cameraPhotoUri);
                Bitmap bitmapMain = scaleBitmap(bitmap, 1200);

                bitmap = cropBitmap(bitmap);
                Bitmap bitmapThumb = Bitmap.createScaledBitmap(bitmap, 300, 300, false);
                bitmap.recycle();

                String imageName = generateImageName();
                photoUri = save_image(bitmapMain, imageName + ".jpg");
                bitmapMain.recycle();
                photoThumbUri = save_image(bitmapThumb, imageName + "_thumb.jpg");
                bitmapThumb.recycle();
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }

            ImageView image = findViewById(R.id.imageView);
            image.setImageURI(Uri.parse(photoUri));
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
        String timeStamp = new SimpleDateFormat(
                "yyyyMMdd_HHmmss",
                Locale.US
        ).format(new Date());
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

    public static Bitmap scaleBitmap(Bitmap srcBmp, int maxDimension) {
        Bitmap dstBmp;
        if (srcBmp.getWidth() >= srcBmp.getHeight()) {
            dstBmp = Bitmap.createScaledBitmap(
                    srcBmp,
                    maxDimension,
                    maxDimension * srcBmp.getHeight() / srcBmp.getWidth(),
                    false
            );

        } else {
            dstBmp = Bitmap.createScaledBitmap(
                    srcBmp,
                    maxDimension * srcBmp.getWidth() / srcBmp.getHeight(),
                    maxDimension,
                    false
            );
        }
        return dstBmp;
    }

}