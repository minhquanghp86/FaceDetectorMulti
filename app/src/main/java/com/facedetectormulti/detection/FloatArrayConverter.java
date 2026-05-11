package com.facedetectormulti.detection;

import androidx.room.TypeConverter;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.List;

public class FloatArrayConverter {
    private static final Gson gson = new Gson();
    
    @TypeConverter
    public static String fromFloatArray(float[] array) {
        if (array == null) return null;
        // Convert float[] → List<Float> → JSON string
        List<Float> list = new java.util.ArrayList<>();
        for (float f : array) list.add(f);
        return gson.toJson(list);
    }
    
    @TypeConverter
    public static float[] toFloatArray(String jsonString) {
        if (jsonString == null) return new float[0];
        Type listType = new TypeToken<List<Float>>() {}.getType();
        List<Float> list = gson.fromJson(jsonString, listType);
        float[] array = new float[list.size()];
        for (int i = 0; i < list.size(); i++) array[i] = list.get(i);
        return array;
    }
}