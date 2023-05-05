package de.fhws.indoor.sensorfingerprintapp;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.net.Uri;
import android.net.wifi.rtt.RangingRequest;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;

import de.fhws.indoor.libsmartphoneindoormap.parser.XMLMapParser;
import de.fhws.indoor.sensorfingerprintapp.R;

/**
 * @author Markus Ebner
 */
public class SettingsFragment extends PreferenceFragmentCompat {
    private Preference prefActiveSensors = null;
    private Preference prefDecawaveUWBTagMacAddress = null;
    private Preference prefFtmBurstSize = null;

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

        prefActiveSensors = findPreference("prefActiveSensors");
        prefDecawaveUWBTagMacAddress = findPreference("prefDecawaveUWBTagMacAddress");
        assert prefDecawaveUWBTagMacAddress != null;
        prefDecawaveUWBTagMacAddress.setEnabled(prefActiveSensors.getPersistedStringSet(new HashSet<>()).contains("DECAWAVE_UWB"));

        prefFtmBurstSize = findPreference("prefFtmBurstSize");
        assert prefFtmBurstSize != null;
        prefFtmBurstSize.setEnabled(prefActiveSensors.getPersistedStringSet(new HashSet<>()).contains("WIFIRTTSCAN"));

        prefFtmBurstSize.setOnPreferenceChangeListener((preference, newValue) -> {
            int burstSize = Integer.parseInt((String)newValue);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // accept 0 for default burst size, reject other invalid sizes
                if (burstSize == 0) {
                    return true;
                } else if (burstSize < RangingRequest.getMinRttBurstSize()) {
                    Toast.makeText(requireContext(), "Burst size too small!", Toast.LENGTH_LONG).show();
                    return false;
                } else if(burstSize > RangingRequest.getMaxRttBurstSize()) {
                    Toast.makeText(requireContext(), "Burst size too big!", Toast.LENGTH_LONG).show();
                    return false;
                }
            }
            return true;
        });

        prefActiveSensors.setOnPreferenceChangeListener((preference, newValue) -> {
            Set<String> activeSensors = (Set<String>) newValue;
            prefDecawaveUWBTagMacAddress.setEnabled(activeSensors.contains("DECAWAVE_UWB"));
            prefFtmBurstSize.setEnabled(activeSensors.contains("WIFIRTTSCAN"));
            return true;
        });
    }

    @NonNull
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

        Uri dst = Uri.fromFile(new File(getActivity().getFilesDir(), MainActivity.MAP_URI));
        try {
            copyMapToAppStorage(uri, dst);
        } catch (IOException e) {
            e.printStackTrace();
        }

        try {
            XMLMapParser parser = new XMLMapParser(getContext());
            MainActivity.currentMap = parser.parse(mContentResolver.openInputStream(dst));
            MainActivity.currentMap.resetSeen(true);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
    }

    private void deleteTmpFingerprintFiles() {
        deleteTmpFingerprintFiles(MainActivity.FINGERPRINTS_TMP_DIR);
    }

    private void deleteTmpFingerprintFiles(String tmpFileDir) {
        File[] files = new File(requireActivity().getFilesDir(), tmpFileDir).listFiles();
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
