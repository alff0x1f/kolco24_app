package ru.kolco24.kolco24.ui.photo;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.ColorDrawable;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import ru.kolco24.kolco24.R;
import ru.kolco24.kolco24.data.SettingsPreferences;
import ru.kolco24.kolco24.data.entities.Photo;

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
    private EditText pointNumberEditField;
    private Uri cameraPhotoUri;

    private int photoId;
    private int pointNumber;
    private String photoUri;
    private String photoThumbUri;
    private String photoTime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int teamId = SettingsPreferences.getSelectedTeamId(getApplicationContext());

        if (teamId == 0) {
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle("Не выбрана команда")
                    .setMessage("Найдите свою команду на вкладке \"Команды\" и выберите её " +
                            "в качестве текущей")
                    .setPositiveButton("Ок", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            finish();
                        }
                    }).setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override
                        public void onCancel(DialogInterface dialogInterface) {
                            finish();
                        }
                    }).create();
            dialog.show();
            return;
        }

        final ActionBar actionBar = getSupportActionBar();
        actionBar.setBackgroundDrawable(
                new ColorDrawable(getResources().getColor(R.color.black))
        );

        setContentView(R.layout.activity_new_word);

        Intent callingIntent = getIntent();
        photoId = callingIntent.getIntExtra("id", 0);
        pointNumber = callingIntent.getIntExtra("point_number", -1);
        photoUri = callingIntent.getStringExtra("photo_uri");
        photoThumbUri = callingIntent.getStringExtra("photo_thumb_uri");
        boolean fromGallery = callingIntent.getBooleanExtra("fromGallery", false);

        if (photoUri  != null) {
            ImageView imageView = findViewById(R.id.imageView);
            imageView.setImageURI(Uri.parse(photoUri));
            TextView pointNumberTextView = findViewById(R.id.pointNumberTextView);
            pointNumberTextView.setText(String.format("%02d", pointNumber));
        }
        if (photoId != 0) {
            actionBar.setTitle("Редактирование фото КП");
        } else {
            actionBar.setTitle("Новое фото");
        }


        if (fromGallery) {
            openGallery(null);
        } else if (photoId == 0) {
            openCamera(null);
        }

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.photo_menu, menu);
        return true;
    }

    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_change_number) {
            requestNumber();
            return true;
        }
        if (item.getItemId() == R.id.action_save || item.getItemId() == R.id.action_save2) {
            savePhoto(null);
            return true;
        }
        if (item.getItemId() == R.id.action_delete) {
            deletePhoto(null);
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
                        if (editTextInput.length() > 0) {
                            pointNumber = Integer.parseInt(editTextInput);
                        }
                        savePhoto(null);
                    }
                })
                .setOnCancelListener(dialogInterface -> finish())
                .setNegativeButton("Отмена", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        finish();
                    }
                })
                .create();
        int margin = dpToPx(20);
        dialog.setView(pointNumberEditField, margin, 0, margin, 0);
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        dialog.show();
        pointNumberEditField.requestFocus();
    }

    public static int dpToPx(int dp) {
        return (int) (dp * Resources.getSystem().getDisplayMetrics().density);
    }

    private void openGallery(View v) {
        Intent takePictureIntent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.INTERNAL_CONTENT_URI);
        try {
            startActivityForResult(takePictureIntent, REQUEST_IMAGE_GALLERY);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Не удалось открыть галерею.",
                    Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void openCamera(View v) {
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        String imageName = generateImageName();
        File photoFile = createImageFile(imageName + "_orig.jpg");
        if (photoFile != null) {
            cameraPhotoUri = FileProvider.getUriForFile(this,
                    "ru.kolco24.kolco24.fileprovider",
                    photoFile);
            takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri);
            try {
                startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE);
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "Не удалось открыть камеру.",
                        Toast.LENGTH_SHORT).show();
                openGallery(null);
            }
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IMAGE_GALLERY) {
            if (resultCode == RESULT_OK) {
                Uri imageUri = data.getData();

                try {
                    Bitmap bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), imageUri);
                    Bitmap bitmapMain = scaleBitmap(bitmap, 2000);

                    bitmap = cropBitmap(bitmap);
                    Bitmap bitmapThumb = Bitmap.createScaledBitmap(bitmap, 300, 300, false);
                    bitmap.recycle();

                    String imageName = generateImageName();
                    updatePhotoTime();
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
                savePhoto(null);
            } else if (photoUri == null) {
                finish();
            }
        }

        if (requestCode == REQUEST_IMAGE_CAPTURE) {
            if (resultCode == RESULT_OK) {
                try {
                    Bitmap bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), cameraPhotoUri);
                    Bitmap bitmapMain = scaleBitmap(bitmap, 2000);

                    bitmap = cropBitmap(bitmap);
                    Bitmap bitmapThumb = Bitmap.createScaledBitmap(bitmap, 300, 300, false);
                    bitmap.recycle();

                    String imageName = generateImageName();
                    updatePhotoTime();
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
                savePhoto(null);
            } else if (photoUri == null) {
                finish();
            }
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

    public void updatePhotoTime() {
        photoTime = new SimpleDateFormat(
                "dd.MM HH:mm",
                Locale.US
        ).format(new Date());
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

    private void savePhoto(View view) {
        if (pointNumber < 0) {
            requestNumber();
            return;
        }
        if (photoUri == null) {
            Toast.makeText(getApplicationContext(), "Выберите фото", Toast.LENGTH_SHORT).show();
            return;
        }

        PhotoViewModel photoViewModel = new PhotoViewModel(getApplication());
        if (photoId == 0) {
            // Create new photo
            int teamId = SettingsPreferences.getSelectedTeamId(getApplicationContext());
            photoViewModel.insert(new Photo(
                    teamId,
                    pointNumber,
                    photoUri,
                    photoThumbUri,
                    photoTime,
                    System.currentTimeMillis(),
                    ""
            ));
        } else {
            // Update photo
            AsyncTask.execute(() -> {
                Photo photo = photoViewModel.getPhotoById(photoId);
                boolean isChanged = false;
                if (photo.getPointNumber() != pointNumber) {
                    photo.setPointNumber(pointNumber);
                    isChanged = true;
                }
                if (!photo.getPhotoUrl().equals(photoUri)) {
                    photo.setPhotoUrl(photoUri);
                    photo.setPhotoThumbUrl(photoThumbUri);
                    photo.setPhotoTime(photoTime);
                    isChanged = true;
                }
                if (isChanged) {
                    photo.setStatus(Photo.NEW);
                    photo.setSyncLocal(false);
                    photo.setSync(false);
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
        finish();
    }

    private void deletePhoto(View view) {
        if (photoId == 0) {
            finish();
            return;
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Удалить фото кп?")
                .setPositiveButton("Удалить", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        PhotoViewModel photoViewModel = new PhotoViewModel(getApplication());
                        AsyncTask.execute(() -> {
                            photoViewModel.deletePhotoPointById(photoId);
                            runOnUiThread(() -> finish());
                        });
                    }
                })
                .setNegativeButton("Отмена", null)
                .create();
        dialog.show();

    }
}