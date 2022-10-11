package de.fhws.indoor.sensorfingerprintapp;

import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.fhws.indoor.libsmartphoneindoormap.model.FingerprintPosition;

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
    private enum FingerprintType {
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
                if(!headerFields.containsKey("name")) { continue; } // bail out on this one! Invalid!
                String name = headerFields.get("name");
                return new FingerprintRecordings.Recording(name, data);
            } else if (type == FingerprintType.PATH) {
                if(!headerFields.containsKey("points")) { continue; } // bail out on this one! Invalid!
                String pointsString = headerFields.get("points");
                if(pointsString == null) { continue; }
                ArrayList<String> points = parseArrayAttribute(pointsString);
                return new FingerprintRecordings.Recording(points, data);
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

    static final Pattern arrayAttributePattern = Pattern.compile("\"([^\"]*)\"");
    private ArrayList<String> parseArrayAttribute(String value) {
        ArrayList<String> result = new ArrayList<>();
        if(!value.startsWith("[") || !value.endsWith("]")) { return null; }
        value = value.substring(1, value.length() - 1);
        for (Matcher m = arrayAttributePattern.matcher(value); m.find(); ) {
            result.add(m.group(1));
        }
        return result;
    }

    private String nextLine() throws IOException {
        if(!this.stream.ready()) { return null; }
        return this.stream.readLine();
    }

}
