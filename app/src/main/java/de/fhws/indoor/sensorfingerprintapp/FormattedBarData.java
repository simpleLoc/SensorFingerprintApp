package de.fhws.indoor.sensorfingerprintapp;


import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.formatter.ValueFormatter;

public class FormattedBarData {
    public BarData barData;
    public ValueFormatter formatter;

    public FormattedBarData(BarData barData, ValueFormatter formatter) {
        this.barData = barData;
        this.formatter = formatter;
    }
}
