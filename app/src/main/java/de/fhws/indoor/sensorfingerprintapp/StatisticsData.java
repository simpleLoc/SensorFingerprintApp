package de.fhws.indoor.sensorfingerprintapp;

import androidx.annotation.Nullable;

import com.github.mikephil.charting.components.AxisBase;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.formatter.ValueFormatter;

import java.text.DecimalFormat;
import java.text.Format;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;

import de.fhws.indoor.libsmartphonesensors.SensorType;

public class StatisticsData {
    public static class SensorTypeData {
        public static class SensorData {
            private final Format format = new DecimalFormat(",#0.0");
            private final String sensorId;
            private final float binSize = 1; // db or meter

            private long sum = 0;
            private final HashMap<Float, Long> histogram = new HashMap<>();

            public SensorData(String sensorName) {
                this.sensorId = sensorName;
            }

            public void put(float x) {
                int binIdx = Math.round(x / binSize);
                Float bin = binIdx * binSize;
                histogram.merge(bin, 1L, Long::sum);
                sum++;
            }

            public HashMap<Float, Long> getHistogram() {
                return histogram;
            }

            public long getSum() {
                return sum;
            }

            public FormattedBarData getBarData() {
                ArrayList<BarEntry> entries = new ArrayList<>();
                for (java.util.Map.Entry<Float, Long> entry : histogram.entrySet()) {
                    entries.add(new BarEntry(entry.getKey(), entry.getValue()));
                }
                BarDataSet set = new BarDataSet(entries, sensorId);
                BarData data = new BarData(set);
                ValueFormatter formatter = new ValueFormatter() {
                    @Override
                    public String getAxisLabel(float value, AxisBase axis) {
                        return format.format(value);
                    }
                };
                return new FormattedBarData(data, formatter);
            }
        }

        private final SensorType sensorType;
        private final HashMap<String, SensorData> sensorData = new HashMap<>();

        private SensorTypeData(SensorType sensorType) {
            this.sensorType = sensorType;
        }

        public void put(String id, float x) {
            getSensorData(id).put(x);
        }

        public SensorData getSensorData(String id) {
            if (!sensorData.containsKey(id)) {
                sensorData.put(id, new SensorData(id));
            }

            return sensorData.get(id);
        }

        public HashMap<String, SensorData> getSensorData() {
            return sensorData;
        }

        public FormattedBarData getBarData(@Nullable Set<String> filter) {
            ArrayList<BarEntry> entries = new ArrayList<>();
            ArrayList<String> ids = new ArrayList<>();
            for (java.util.Map.Entry<String, SensorData> entry : sensorData.entrySet()) {
                if (filter == null || filter.contains(entry.getKey())) {
                    entries.add(new BarEntry(ids.size(), entry.getValue().getSum()));
                    ids.add(entry.getKey());
                }
            }
            BarDataSet set = new BarDataSet(entries, sensorType.toString());
            BarData data = new BarData(set);
            ValueFormatter formatter = new ValueFormatter() {
                @Override
                public String getAxisLabel(float value, AxisBase axis) {
                    if (ids.isEmpty()) {
                        return "No Formatter";
                    }
                    return ids.get(Math.min(Math.max(0, Math.round(value)), ids.size()-1));
                }
            };
            return new FormattedBarData(data, formatter);
        }
    }

    private final HashMap<SensorType, SensorTypeData> statisticsData = new HashMap<>();

    public void put(SensorType t, String id, float x) {
        getSensorTypeData(t).getSensorData(id).put(x);
    }

    public SensorTypeData getSensorTypeData(SensorType t) {
        if (!statisticsData.containsKey(t)) {
            statisticsData.put(t, new SensorTypeData(t));
        }

        return statisticsData.get(t);
    }

    public void clear() {
        statisticsData.clear();
    }
}
