package org.kolco24.kolco24;

import androidx.appcompat.app.AppCompatActivity;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;

public class NewPhotoActivity extends AppCompatActivity {
    public static final String POINT_NAME = "org.kolco24.kolco24.newPhoto.pointName";
    public static final String PHOTO_URI = "org.kolco24.kolco24.newPhoto.photoUri";
    static final int REQUEST_IMAGE_GALLERY = 2;
    private EditText mPointNameEditView;
    private Uri imageUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_new_word);
        mPointNameEditView = findViewById(R.id.edit_word);

        final Button saveButton = findViewById(R.id.button_save);
        saveButton.setOnClickListener(view -> {
            Intent replyIntent = new Intent();
            if (TextUtils.isEmpty(mPointNameEditView.getText()) || imageUri == null) {
                setResult(RESULT_CANCELED, replyIntent);
            } else {
                String word = mPointNameEditView.getText().toString();
                replyIntent.putExtra(POINT_NAME, word);
                replyIntent.putExtra(PHOTO_URI, imageUri.toString());
                setResult(RESULT_OK, replyIntent);
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
            imageUri = data.getData();
            ImageView image = findViewById(R.id.imageView);
            image.setImageURI(imageUri);

            Button galleryButton = findViewById(R.id.button_gallery);
            galleryButton.setText("Выбрать другое фото");
        }
    }
}