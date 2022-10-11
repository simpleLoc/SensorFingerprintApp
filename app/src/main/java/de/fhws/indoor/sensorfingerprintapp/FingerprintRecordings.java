package de.fhws.indoor.sensorfingerprintapp;

import java.io.File;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.UUID;

import de.fhws.indoor.libsmartphoneindoormap.model.Fingerprint;

public class FingerprintRecordings {
    private final HashMap<String, Recording> recordings = new HashMap<>();

    public static class Recording {
        private UUID id = null;

        String fingerprintName = null;
        ArrayList<String> fingerprintNames = null;
        String data = null;

        public Recording(String fingerprintName, String data) {
            this.id = UUID.randomUUID();
            this.fingerprintName = fingerprintName;
            this.data = data;
        }

        public Recording(ArrayList<String> fingerprintNames, String data) {
            this.id = UUID.randomUUID();

            // create a name from points
            StringBuilder sb = new StringBuilder();
            for (String name :  fingerprintNames) {
                sb.append(String.format(Locale.US, "%s -> ", name));
            }
            sb.delete(sb.length()-3, sb.length()-1);
            this.fingerprintName = sb.toString();

            this.fingerprintNames = fingerprintNames;
            this.data = data;
        }

        public UUID getId() {
            return id;
        }

        public void setRecordingFile(File file) {
            id = UUID.fromString(file.getName().replace(MainActivity.FINGERPRINTS_EXTENSION, ""));
        }
    }

    public FingerprintRecordings() {

    }

    public void addRecording(Recording recording) {
        recordings.put(recording.fingerprintName, recording);
    }

    public Recording getRecording(Fingerprint fingerprint) {
        return recordings.get(fingerprint.name);
    }

    public boolean contains(Fingerprint fingerprint) {
        return recordings.containsKey(fingerprint.name);
    }
}
