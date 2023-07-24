package de.fhws.indoor.sensorfingerprintapp;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.UUID;

import de.fhws.indoor.libsmartphoneindoormap.model.Fingerprint;
import de.fhws.indoor.libsmartphoneindoormap.model.FingerprintPath;
import de.fhws.indoor.libsmartphoneindoormap.model.FingerprintPosition;
import de.fhws.indoor.libsmartphoneindoormap.model.Vec3;

public class FingerprintRecordings {
    private final HashMap<String, Recording> recordings = new HashMap<>();

    public static class Recording {
        private UUID id = null;

        private final Fingerprint fingerprint;
        String data = null;

        public Recording(Fingerprint fingerprint, String data) {
            this.id = UUID.randomUUID();
            this.fingerprint = fingerprint;
            this.data = data;
        }

        public UUID getId() {
            return id;
        }

        public void setId(UUID id) {
            this.id = id;
        }

        public Fingerprint getFingerprint() {
            return fingerprint;
        }
    }

    public FingerprintRecordings() {

    }

    public HashMap<String, Recording> getRecordings() {
        return recordings;
    }

    public void addRecording(Recording recording) {
        recordings.put(recording.fingerprint.name, recording);
    }

    public Recording getRecording(Fingerprint fingerprint) {
        return recordings.get(fingerprint.name);
    }

    public boolean contains(Fingerprint fingerprint) {
        return recordings.containsKey(fingerprint.name);
    }
}
