package de.fhws.indoor.sensorfingerprintapp;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceFragmentCompat;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import de.fhws.indoor.libsmartphoneindoormap.parser.XMLMapParser;
import de.fhws.sensorfingerprintapp.R;

/**
 * @author Markus Ebner
 */
public class SettingsFragment extends PreferenceFragmentCompat {

    ContentResolver mContentResolver = null;
    ActivityResultLauncher<String> mGetContent = registerForActivityResult(
        new ActivityResultContracts.GetContent(),
            uri -> {
                AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
                builder
                        .setTitle(R.string.select_map_dialog_title)
                        .setMessage(R.string.select_map_dialog_msg)
                        .setNegativeButton(R.string.select_map_dialog_negative, null)
                        .setPositiveButton(R.string.select_map_dialog_positive, (DialogInterface dialog, int id) -> selectMap(uri));

                AlertDialog dialog = builder.create();
                dialog.show();
            });


    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.preferences, rootKey);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        LinearLayout v = (LinearLayout)super.onCreateView(inflater, container, savedInstanceState);

        Button btnSelectMap = new Button(getActivity().getApplicationContext());
        btnSelectMap.setText(R.string.select_map_button_text);
        v.addView(btnSelectMap);
        btnSelectMap.setOnClickListener(btn -> handleSelectMap());

        Button btnResetSeen = new Button(getActivity().getApplicationContext());
        btnResetSeen.setText(R.string.reset_seen_button_text);
        v.addView(btnResetSeen);
        btnResetSeen.setOnClickListener(btn -> {
            if (MainActivity.currentMap != null) {
                MainActivity.currentMap.resetSeen(true);
            }
        });

        return v;
    }


    private void handleSelectMap() {
        mContentResolver = getActivity().getContentResolver();
        mGetContent.launch("*/*");
    }

    private void selectMap(Uri uri) {
        // delete fingerprint files that were recorded with the previous map
        deleteTmpFingerprintFiles();

        Uri dst = Uri.fromFile(new File(getActivity().getExternalFilesDir(null), MainActivity.MAP_URI));
        try {
            copyMapToAppStorage(uri, dst);
        } catch (IOException e) {
            e.printStackTrace();
        }

        try {
            XMLMapParser parser = new XMLMapParser(getContext());
            MainActivity.currentMap = parser.parse(mContentResolver.openInputStream(dst));
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
    }

    private void deleteTmpFingerprintFiles() {
        deleteTmpFingerprintFiles(MainActivity.FINGERPRINTS_TMP_DIR_BLE);
        deleteTmpFingerprintFiles(MainActivity.FINGERPRINTS_TMP_DIR_WIFI);
    }

    private void deleteTmpFingerprintFiles(String tmpFileDir) {
        File[] files = new File(requireActivity().getExternalFilesDir(null), tmpFileDir).listFiles();
        if (files != null) {
            for (File fpFile : files) {
                if (!fpFile.delete()) {
                    Log.w(MainActivity.STREAM_TAG, "Cannot delete file: " + Uri.fromFile(fpFile));
                }
            }
        }
    }

    // To guarantee that the app still has access to the chosen map, we copy
    // it into the app's local storage path - where the app always has
    // read access.
    private void copyMapToAppStorage(Uri src, Uri dst) throws IOException {
        try (InputStream in = mContentResolver.openInputStream(src)) {
            try (OutputStream out = mContentResolver.openOutputStream(dst, "wt")) {
                // Transfer bytes from in to out
                byte[] buf = new byte[1024];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
            }
        }
    }
}
