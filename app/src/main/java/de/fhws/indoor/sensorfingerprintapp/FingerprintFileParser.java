package de.fhws.indoor.sensorfingerprintapp;

import android.widget.Toast;

import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.temporal.ValueRange;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import de.fhws.indoor.libsmartphoneindoormap.model.FingerprintPath;
import de.fhws.indoor.libsmartphoneindoormap.model.FingerprintPosition;
import de.fhws.indoor.libsmartphoneindoormap.model.Vec3;

public class FingerprintFileParser {
    public static final String FINGERPRINT_POINT_TAG = "[fingerprint:point]";
    public static final String FINGERPRINT_PATH_TAG = "[fingerprint:path]";

    public enum WhatToParse {
        ONLY_HEADER,
        HEADER_AND_DATA
    }

    private final BufferedReader stream;
    private final WhatToParse whatToParse;

    public FingerprintFileParser(InputStream fileStream, WhatToParse whatToParse) {
        this.stream = new BufferedReader(new InputStreamReader(fileStream));
        this.whatToParse = whatToParse;
    }

    public ArrayList<FingerprintRecordings.Recording> parse() throws IOException {
        ArrayList<FingerprintRecordings.Recording> recordings = new ArrayList<>();
        while(this.stream.ready()) {
            FingerprintRecordings.Recording rec = this.parseNextFingerprint();
            if (rec != null) { recordings.add(rec); }
        }
        return recordings;
    }


    /* parsing */
    public enum FingerprintType {
        POINT,
        PATH
    }
    private FingerprintRecordings.Recording parseNextFingerprint() throws IOException {
        while(true) {
            FingerprintType type = parseHeader();
            if (type == null) {
                return null;
            }
            HashMap<String, String> headerFields = parseHeaderFields();
            String data = null;
            if(whatToParse == WhatToParse.HEADER_AND_DATA) {
                data = parseData();
            }
            if (type == FingerprintType.POINT) {
                String name = headerFields.get("name");
                String floorIdxString = headerFields.get("floorIdx");
                String floorName = headerFields.get("floorName");
                Vec3 position = parseVec3(headerFields.get("position"));

                if(name == null || floorIdxString == null || floorName == null || position == null) {
                    continue; // bail out on this one! Invalid!
                }

                int floorIdx = Integer.parseInt(floorIdxString);

                FingerprintPosition fpPos = new FingerprintPosition(name, floorIdx, floorName, false, true, position);
                return new FingerprintRecordings.Recording(fpPos, data);
            } else if (type == FingerprintType.PATH) {
                String name = headerFields.get("name");
                String floorIdxString = headerFields.get("floorIdx");
                String floorName = headerFields.get("floorName");
                ArrayList<String> fingerprintNames = parseStringArrayAttribute(headerFields, "points");
                ArrayList<Vec3> positions = parseVecArrayAttribute(headerFields, "positions");

                if(name == null || floorIdxString == null || floorName == null || fingerprintNames == null || positions == null) {
                    continue; // bail out on this one! Invalid!
                }

                int floorIdx = Integer.parseInt(floorIdxString);

                ArrayList<FingerprintPosition> fingerprintPositions = new ArrayList<>();
                for (int i = 0; i < fingerprintNames.size(); ++i) {
                    String fpName = fingerprintNames.get(i);
                    Vec3 position = positions.get(i);

                    FingerprintPosition fpPos = new FingerprintPosition(fpName, floorIdx, floorName, false, false, position);
                    fingerprintPositions.add(fpPos);
                }

                FingerprintPath fpPath = new FingerprintPath(floorIdx, floorName, false, true, fingerprintPositions);
                return new FingerprintRecordings.Recording(fpPath, data);
            }
        }
    }

    private FingerprintType parseHeader() throws IOException {
        while(true) {
            String line = nextLine();
            if(line == null) { return null; }
            line = line.trim();
            if(line.equals(FINGERPRINT_POINT_TAG)) {
                return FingerprintType.POINT;
            } else if(line.equals(FINGERPRINT_PATH_TAG)) {
                return FingerprintType.PATH;
            }
            // otherwise, skip line
        }
    }

    @NonNull
    private HashMap<String, String> parseHeaderFields() throws IOException {
        HashMap<String, String> result = new HashMap<>();
        while(true) {
            String line = nextLine();
            if(line == null || line.equals("")) { return result; } // reached end, return result
            String[] segments = line.split("=", 2);
            if(segments.length == 2) {
                result.put(segments[0], segments[1]);
            }
        }
    }

    private String parseData() throws IOException {
        StringBuilder buffer = new StringBuilder();
        while(true) {
            String line = nextLine();
            if(line == null) { return null; }
            if(line.equals("")) { return buffer.toString(); } // reached end
            buffer.append(line); buffer.append('\n');
        }
    }

    static Vec3 parseVec3(String vecStr) {
        if(vecStr == null) { return null; }
        if(!vecStr.startsWith("(") || !vecStr.endsWith(")")) { return null; }
        vecStr = vecStr.substring(1, vecStr.length() - 1);
        String[] vecParts = vecStr.split(";");
        if(vecParts.length != 3) { return null; }
        return new Vec3(Float.parseFloat(vecParts[0]), Float.parseFloat(vecParts[1]), Float.parseFloat(vecParts[2]));
    }

    private ArrayList<String> parseStringArrayAttribute(HashMap<String, String> headerFields, String arrayName) {
        if(!headerFields.containsKey(arrayName + "[]")) { return null; }
        int arrLen = Integer.parseInt(Objects.requireNonNull(headerFields.get(arrayName + "[]")));
        ArrayList<String> result = new ArrayList<>();
        for(int i = 0; i < arrLen; ++i) {
            result.add(headerFields.get(arrayName + "[" + i + "]"));
        }
        return result;
    }

    private ArrayList<Vec3> parseVecArrayAttribute(HashMap<String, String> headerFields, String arrayName) {
        return new ArrayList<>(parseStringArrayAttribute(headerFields, arrayName).stream()
                .map(s -> parseVec3(s)).filter(v -> v != null)
                .collect(Collectors.toList()));
    }

    private String nextLine() throws IOException {
        if(!this.stream.ready()) { return null; }
        return this.stream.readLine();
    }

}
