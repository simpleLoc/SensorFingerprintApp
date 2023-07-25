package de.fhws.indoor.sensorfingerprintapp;

import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import de.fhws.indoor.libsmartphoneindoormap.model.FingerprintPosition;
import de.fhws.indoor.libsmartphoneindoormap.model.Floor;
import de.fhws.indoor.libsmartphoneindoormap.model.Map;

/**
 * A simple {@link Fragment} subclass.
 * Use the {@link DialogCreateFingerprint#newInstance} factory method to
 * create an instance of this fragment.
 */
public class DialogCreateFingerprint extends DialogFragment {

    FingerprintPosition fingerprint;

    Map currentMap;
    DialogInterface.OnClickListener onCreateListener = null;

    public DialogCreateFingerprint() {
        // Required empty public constructor
    }

    public DialogCreateFingerprint(FingerprintPosition fingerprint, Map currentMap) {
        this.fingerprint = fingerprint;
        this.currentMap = currentMap;
    }

    public void setOnCreateListener(DialogInterface.OnClickListener onCreateListener) {
        this.onCreateListener = onCreateListener;
    }

    public static DialogCreateFingerprint newInstance(String title) {
        DialogCreateFingerprint fragment = new DialogCreateFingerprint();
        Bundle args = new Bundle();
        args.putString("title", title);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        // Get the layout inflater
        LayoutInflater inflater = requireActivity().getLayoutInflater();

        View dialog = inflater.inflate(R.layout.fragment_dialog_create_fingerprint, null);
        EditText etName = dialog.findViewById(R.id.etName);
        etName.setText(fingerprint.name);

        List<String> spFloorNames = currentMap.getFloors().stream().map(Floor::getName).collect(Collectors.toList());

        Spinner spFloorName = dialog.findViewById(R.id.spFloorName);
        ArrayAdapter<String> spFloorNameAdapter = new ArrayAdapter<String>(
                getContext(), android.R.layout.simple_spinner_item, spFloorNames);

        spFloorNameAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spFloorName.setAdapter(spFloorNameAdapter);

        spFloorName.setSelection(fingerprint.floorIdx);

        EditText etX = dialog.findViewById(R.id.etX);
        etX.setText(String.format("%s", fingerprint.position.x));
        EditText etY = dialog.findViewById(R.id.etY);
        etY.setText(String.format("%s", fingerprint.position.y));
        EditText etZ = dialog.findViewById(R.id.etZ);
        etZ.setText(String.format("%s", fingerprint.position.z));

        builder.setView(dialog)
                // Add action buttons
                .setPositiveButton(R.string.dialog_create_fingerprint_create, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        fingerprint.name = etName.getText().toString();
                        fingerprint.floorIdx = spFloorName.getSelectedItemPosition();
                        fingerprint.floorName = spFloorName.getSelectedItem().toString();
                        fingerprint.position.x = Float.parseFloat(etX.getText().toString());
                        fingerprint.position.y = Float.parseFloat(etY.getText().toString());
                        fingerprint.position.z = Float.parseFloat(etZ.getText().toString());
                        DialogCreateFingerprint.this.onCreateListener.onClick(dialogInterface, i);
                    }
                })
                .setNegativeButton(R.string.dialog_create_fingerprint_cancel, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        DialogCreateFingerprint.this.getDialog().cancel();
                    }
                });
        return builder.create();

    }
}