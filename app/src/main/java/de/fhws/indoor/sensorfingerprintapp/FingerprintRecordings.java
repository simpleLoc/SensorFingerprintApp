package de.fhws.indoor.sensorfingerprintapp;

import java.io.File;
import java.util.HashMap;

import de.fhws.indoor.libsmartphoneindoormap.model.Fingerprint;

public class FingerprintRecordings {
    private final HashMap<String, Recording> recordings = new HashMap<>();

    public static class Recording {
        Fingerprint fingerprint = null;
        File recordingFile = null;

        String data = null;

        public Recording(Fingerprint fingerprint, String data) {
            this.fingerprint = fingerprint;
            this.data = data;
        }
    }

    public FingerprintRecordings() {

    }

    public void addRecording(Recording recording) {
        recordings.put(recording.fingerprint.name, recording);
    }

    public Recording getRecording(Fingerprint fingerprint) {
        return recordings.get(fingerprint.name);
    }
}
