package de.fhws.indoor.sensorfingerprintapp;

import org.intellij.lang.annotations.Language;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

import de.fhws.indoor.libsmartphoneindoormap.model.Fingerprint;
import de.fhws.indoor.libsmartphoneindoormap.model.FingerprintPath;
import de.fhws.indoor.libsmartphoneindoormap.model.FingerprintPosition;
import de.fhws.indoor.libsmartphoneindoormap.model.Vec3;

public class FingerprintFileSerializer {

    private static String serializeVec3(Vec3 vec3) {
        return String.format(Locale.US, "(%f;%f;%f)", vec3.x, vec3.y, vec3.z);
    }

    private static <T> void serializeAttributeVec(OutputStreamWriter outWriter, String attrName, List<T> array) throws IOException {
        serializeAttributeVec(outWriter, attrName, array, Objects::toString);
    }

    private static <T> void serializeAttributeVec(OutputStreamWriter outWriter, String attrName, List<T> array, Function<T, String> itemSerializerFn) throws IOException {
        outWriter.write(attrName + "[]=" + array.size() + "\n");
        for(int i = 0; i < array.size(); ++i) {
            outWriter.write(attrName + "[" + i + "]=" + itemSerializerFn.apply(array.get(i)) + "\n");
        }
    }

    public static void writeHeader(OutputStream out, Fingerprint fingerprint) throws IOException {
        OutputStreamWriter outWriter = new OutputStreamWriter(out);
        if (fingerprint instanceof FingerprintPosition) {
            FingerprintPosition fpPos = (FingerprintPosition) fingerprint;
            outWriter.write(FingerprintFileParser.FINGERPRINT_POINT_TAG + "\n");
            outWriter.write("name=" + fpPos.name + "\n");
            outWriter.write("floorIdx=" + fpPos.floorIdx + "\n");
            outWriter.write("floorName=" + fpPos.floorName + "\n");
            outWriter.write("position=" + serializeVec3(fpPos.position) + "\n");
        } else if (fingerprint instanceof FingerprintPath) {
            FingerprintPath fpPath = (FingerprintPath) fingerprint;
            outWriter.write(FingerprintFileParser.FINGERPRINT_PATH_TAG + "\n");
            outWriter.write("name=" + fpPath.name + "\n");
            serializeAttributeVec(outWriter, "floorIdxs", fpPath.floorIdxs);
            serializeAttributeVec(outWriter, "floorNames", fpPath.floorNames);
            serializeAttributeVec(outWriter, "points", fpPath.fingerprintNames);
            serializeAttributeVec(outWriter, "positions", fpPath.positions, FingerprintFileSerializer::serializeVec3);
        }
        outWriter.write("\n");
        // don't close, because it's only a temporary wrapper around the underlying OutputStream
        outWriter.flush();
    }

}
