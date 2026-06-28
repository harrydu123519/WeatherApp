package com.example.weatherapp;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int PERM_REQUEST = 1001;
    private static final long REFRESH_MS  = 60_000;

    private Handler handler;
    private Runnable refreshTask;
    private ExecutorService executor;

    private FrameLayout rootLayout;
    private ProgressBar progressBar;
    private View contentView;

    private TextView tvLocation, tvTemp, tvDesc, tvTodayRange, tvFeelsLike;
    private TextView tvHumidity, tvWind, tvPressure, tvVisibility, tvUv, tvCloud;
    private TextView tvUpdate;

    private TextView tvF0Day, tvF0Icon, tvF0Desc, tvF0Range;
    private TextView tvF1Day, tvF1Icon, tvF1Desc, tvF1Range;
    private TextView tvF2Day, tvF2Icon, tvF2Desc, tvF2Range;

    private RainView rainView;

    private double cachedLat = 0, cachedLon = 0;
    private boolean hasLocation = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        rootLayout   = findViewById(R.id.root_layout);
        progressBar  = findViewById(R.id.progress_bar);
        contentView  = findViewById(R.id.content_view);
        tvLocation   = findViewById(R.id.tv_location);
        tvTemp       = findViewById(R.id.tv_temp);
        tvDesc       = findViewById(R.id.tv_desc);
        tvTodayRange = findViewById(R.id.tv_today_range);
        tvFeelsLike  = findViewById(R.id.tv_feels_like);
        tvHumidity   = findViewById(R.id.tv_humidity);
        tvWind       = findViewById(R.id.tv_wind);
        tvPressure   = findViewById(R.id.tv_pressure);
        tvVisibility = findViewById(R.id.tv_visibility);
        tvUv         = findViewById(R.id.tv_uv);
        tvCloud      = findViewById(R.id.tv_cloud);
        tvUpdate     = findViewById(R.id.tv_update);

        tvF0Day = findViewById(R.id.tv_f0_day); tvF0Icon = findViewById(R.id.tv_f0_icon);
        tvF0Desc = findViewById(R.id.tv_f0_desc); tvF0Range = findViewById(R.id.tv_f0_range);
        tvF1Day = findViewById(R.id.tv_f1_day); tvF1Icon = findViewById(R.id.tv_f1_icon);
        tvF1Desc = findViewById(R.id.tv_f1_desc); tvF1Range = findViewById(R.id.tv_f1_range);
        tvF2Day = findViewById(R.id.tv_f2_day); tvF2Icon = findViewById(R.id.tv_f2_icon);
        tvF2Desc = findViewById(R.id.tv_f2_desc); tvF2Range = findViewById(R.id.tv_f2_range);

        rainView = findViewById(R.id.rain_view);

        executor = Executors.newSingleThreadExecutor();
        handler  = new Handler(Looper.getMainLooper());

        refreshTask = () -> {
            resolveLocationAndFetch();
            handler.postDelayed(refreshTask, REFRESH_MS);
        };

        checkPermissions();
    }

    // ── Location & permissions ──────────────────────────────────────────────

    private void checkPermissions() {
        boolean fine = ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (fine || coarse) startRefreshing();
        else ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                             Manifest.permission.ACCESS_COARSE_LOCATION}, PERM_REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] p, @NonNull int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        startRefreshing();
    }

    private void startRefreshing() {
        handler.removeCallbacks(refreshTask);
        handler.post(refreshTask);
    }

    private void resolveLocationAndFetch() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
            || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            Location loc = null;
            try {
                loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (loc == null) loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (loc == null) loc = lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
            } catch (Exception ignored) {}
            if (loc != null) {
                cachedLat = loc.getLatitude(); cachedLon = loc.getLongitude();
                hasLocation = true;
            }
        }
        fetchWeather();
    }

    // ── Network ─────────────────────────────────────────────────────────────

    @SuppressWarnings("deprecation")
    private void fetchWeather() {
        executor.execute(() -> {
            // 1. Chinese city name via Geocoder
            String chineseCity = null;
            if (hasLocation) {
                try {
                    if (Geocoder.isPresent()) {
                        Geocoder geo = new Geocoder(MainActivity.this, Locale.SIMPLIFIED_CHINESE);
                        List<Address> addrs = geo.getFromLocation(cachedLat, cachedLon, 1);
                        if (addrs != null && !addrs.isEmpty()) {
                            Address a = addrs.get(0);
                            String city = a.getLocality();
                            if (city == null || city.isEmpty()) city = a.getSubAdminArea();
                            if (city == null || city.isEmpty()) city = a.getAdminArea();
                            chineseCity = city;
                        }
                    }
                } catch (Exception ignored) {}
            }

            // 2. Fetch wttr.in JSON
            final String cityForDisplay = chineseCity;
            try {
                String query = hasLocation
                        ? String.format(Locale.US, "%.4f,%.4f", cachedLat, cachedLon) : "";
                URL url = new URL("https://wttr.in/" + query + "?format=j1");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15_000); conn.setReadTimeout(15_000);
                conn.setRequestProperty("User-Agent", "WeatherApp-Android/1.0");
                if (conn.getResponseCode() != 200) {
                    showError("服务暂时不可用 (" + conn.getResponseCode() + ")");
                    conn.disconnect(); return;
                }
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                    String line; while ((line = br.readLine()) != null) sb.append(line);
                }
                conn.disconnect();
                parseAndShow(sb.toString(), cityForDisplay);
            } catch (Exception e) {
                showError("网络错误，稍后重试");
            }
        });
    }

    // ── Parse & display ─────────────────────────────────────────────────────

    private void parseAndShow(String json, String chineseCityOverride) {
        try {
            JSONObject root = new JSONObject(json);
            JSONObject cur  = root.getJSONArray("current_condition").getJSONObject(0);
            JSONArray  wx   = root.getJSONArray("weather");   // up to 3 forecast days

            // Current conditions
            int    tempC      = Integer.parseInt(cur.getString("temp_C"));
            int    feelsLike  = Integer.parseInt(cur.getString("FeelsLikeC"));
            int    humidity   = Integer.parseInt(cur.getString("humidity"));
            int    windKmph   = Integer.parseInt(cur.getString("windspeedKmph"));
            String windDir    = cur.getString("winddir16Point");
            int    pressure   = Integer.parseInt(cur.getString("pressure"));
            int    visibility = Integer.parseInt(cur.getString("visibility"));
            int    uvIndex    = cur.has("uvIndex") ? Integer.parseInt(cur.getString("uvIndex")) : 0;
            int    cloudCover = cur.has("cloudcover") ? Integer.parseInt(cur.getString("cloudcover")) : 0;
            int    code       = Integer.parseInt(cur.getString("weatherCode"));
            String descEn     = cur.getJSONArray("weatherDesc").getJSONObject(0).getString("value");

            // Today max/min from forecast day 0
            JSONObject day0 = wx.getJSONObject(0);
            int maxC0 = Integer.parseInt(day0.getString("maxtempC"));
            int minC0 = Integer.parseInt(day0.getString("mintempC"));
            int code0 = Integer.parseInt(day0.getJSONArray("hourly").getJSONObject(4).getString("weatherCode"));

            // Forecast day 1
            JSONObject day1 = wx.length() > 1 ? wx.getJSONObject(1) : null;
            int maxC1 = 0, minC1 = 0, code1 = 113;
            if (day1 != null) {
                maxC1 = Integer.parseInt(day1.getString("maxtempC"));
                minC1 = Integer.parseInt(day1.getString("mintempC"));
                code1 = Integer.parseInt(day1.getJSONArray("hourly").getJSONObject(4).getString("weatherCode"));
            }

            // Forecast day 2
            JSONObject day2 = wx.length() > 2 ? wx.getJSONObject(2) : null;
            int maxC2 = 0, minC2 = 0, code2 = 113;
            if (day2 != null) {
                maxC2 = Integer.parseInt(day2.getString("maxtempC"));
                minC2 = Integer.parseInt(day2.getString("mintempC"));
                code2 = Integer.parseInt(day2.getJSONArray("hourly").getJSONObject(4).getString("weatherCode"));
            }

            // Location
            JSONObject area = root.getJSONArray("nearest_area").getJSONObject(0);
            String location;
            if (chineseCityOverride != null && !chineseCityOverride.isEmpty()) {
                location = chineseCityOverride;
            } else {
                String areaName = area.getJSONArray("areaName").getJSONObject(0).getString("value");
                String country  = area.getJSONArray("country").getJSONObject(0).getString("value");
                location = areaName.isEmpty() ? country : areaName + ", " + country;
            }

            // Derived strings
            String descZh    = codeToZh(code, descEn);
            String windDirZh = windDirToZh(windDir);
            String uvLabel   = uvToZh(uvIndex);
            String timeStr   = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            boolean raining  = isRaining(code);
            int bgRes        = backgroundFor(code);

            // Day labels for forecast
            String d1label = "明天";
            String d2label = dayOfWeekLabel(2);

            final int _maxC0=maxC0,_minC0=minC0,_code0=code0;
            final int _maxC1=maxC1,_minC1=minC1,_code1=code1;
            final int _maxC2=maxC2,_minC2=minC2,_code2=code2;
            final int _tempC=tempC,_feelsLike=feelsLike,_humidity=humidity;
            final int _windKmph=windKmph,_pressure=pressure,_visibility=visibility;
            final int _uvIndex=uvIndex,_cloudCover=cloudCover;
            final String _loc=location,_descZh=descZh,_windDirZh=windDirZh;
            final String _uvLabel=uvLabel,_timeStr=timeStr;
            final String _d1label=d1label,_d2label=d2label;

            runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);
                contentView.setVisibility(View.VISIBLE);

                // Dynamic background
                rootLayout.setBackgroundResource(bgRes);

                // Current weather
                tvLocation.setText("📍 " + _loc);
                tvTemp.setText(_tempC + "°");
                tvDesc.setText(_descZh);
                tvTodayRange.setText(codeToEmoji(_code0) + "  最高 " + _maxC0 + "°  最低 " + _minC0 + "°");
                tvFeelsLike.setText("体感温度 " + _feelsLike + "°C");
                tvHumidity.setText(_humidity + "%");
                tvWind.setText(_windKmph + " km/h\n" + _windDirZh);
                tvPressure.setText(_pressure + " hPa");
                tvVisibility.setText(_visibility + " km");
                tvUv.setText(_uvIndex + "  " + _uvLabel);
                tvCloud.setText(_cloudCover + "%");

                // Forecast rows
                tvF0Day.setText("今天"); tvF0Icon.setText(codeToEmoji(_code0));
                tvF0Desc.setText(codeToZh(_code0, "")); tvF0Range.setText(_minC0 + "° ~ " + _maxC0 + "°");

                tvF1Day.setText(_d1label); tvF1Icon.setText(codeToEmoji(_code1));
                tvF1Desc.setText(codeToZh(_code1, "")); tvF1Range.setText(_minC1 + "° ~ " + _maxC1 + "°");

                tvF2Day.setText(_d2label); tvF2Icon.setText(codeToEmoji(_code2));
                tvF2Desc.setText(codeToZh(_code2, "")); tvF2Range.setText(_minC2 + "° ~ " + _maxC2 + "°");

                tvUpdate.setText("⏱ 上次更新：" + _timeStr + "   每分钟自动刷新");

                if (raining) rainView.startRain(); else rainView.stopRain();
            });

        } catch (Exception e) {
            showError("数据解析失败: " + e.getMessage());
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private boolean isRaining(int code) {
        return (code >= 176 && code <= 189) || code == 200
            || (code >= 263 && code <= 314)
            || (code >= 353 && code <= 359)
            || (code >= 386 && code <= 389);
    }

    private int backgroundFor(int code) {
        if (code == 113 || code == 116)           return R.drawable.bg_sunny;
        if (isRaining(code))                      return R.drawable.bg_rain;
        if (code >= 200 && code <= 230)           return R.drawable.bg_rain;
        return R.drawable.bg_cloudy;
    }

    private String uvToZh(int uv) {
        if (uv <= 2) return "低";
        if (uv <= 5) return "中等";
        if (uv <= 7) return "高";
        if (uv <= 10) return "很高";
        return "极高";
    }

    private String dayOfWeekLabel(int daysAhead) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, daysAhead);
        int dow = cal.get(Calendar.DAY_OF_WEEK);
        String[] names = {"", "周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        return names[dow];
    }

    private void showError(String msg) {
        runOnUiThread(() -> {
            progressBar.setVisibility(View.GONE);
            contentView.setVisibility(View.VISIBLE);
            tvUpdate.setText("⚠ " + msg);
        });
    }

    private String codeToEmoji(int code) {
        if (code == 113)                      return "☀️";
        if (code == 116)                      return "⛅";
        if (code == 119 || code == 122)       return "☁️";
        if (code == 143)                      return "🌁";
        if (code == 248 || code == 260)       return "🌫️";
        if (code >= 176 && code <= 189)       return "🌦️";
        if (code == 200)                      return "⛈️";
        if (code >= 227 && code <= 230)       return "🌨️";
        if (code >= 263 && code <= 284)       return "🌧️";
        if (code >= 293 && code <= 314)       return "🌧️";
        if (code >= 317 && code <= 338)       return "🌨️";
        if (code >= 350 && code <= 377)       return "🌨️";
        if (code >= 386 && code <= 395)       return "⛈️";
        return "🌈";
    }

    private String codeToZh(int code, String fallback) {
        switch (code) {
            case 113: return "晴天";
            case 116: return "多云";
            case 119: return "阴天";
            case 122: return "阴云密布";
            case 143: return "薄雾";
            case 176: return "局部小雨";
            case 179: return "局部小雪";
            case 182: return "局部冻雨";
            case 185: return "局部冻毛毛雨";
            case 200: return "局部雷阵雨";
            case 227: return "大风雪";
            case 230: return "暴风雪";
            case 248: return "大雾";
            case 260: return "冰雾";
            case 263: case 266: return "毛毛雨";
            case 281: return "冻毛毛雨";
            case 284: return "大冻毛毛雨";
            case 293: case 296: return "小雨";
            case 299: case 302: return "中雨";
            case 305: case 308: return "大雨";
            case 311: return "冻雨";
            case 314: return "大冻雨";
            case 317: case 320: return "雨夹雪";
            case 323: case 326: return "小雪";
            case 329: case 332: return "中雪";
            case 335: case 338: return "大雪";
            case 350: return "冰雹";
            case 353: return "小阵雨";
            case 356: return "中阵雨";
            case 359: return "暴雨";
            case 362: case 365: return "雨夹雪阵";
            case 368: return "小雪阵";
            case 371: return "大雪阵";
            case 374: case 377: return "冰粒";
            case 386: return "雷阵雨";
            case 389: return "强雷暴雨";
            case 392: return "雷阵雪";
            case 395: return "强雷阵雪";
            default:  return fallback;
        }
    }

    private String windDirToZh(String dir) {
        switch (dir) {
            case "N": return "北风"; case "NNE": return "北偏东风";
            case "NE": return "东北风"; case "ENE": return "东偏北风";
            case "E": return "东风"; case "ESE": return "东偏南风";
            case "SE": return "东南风"; case "SSE": return "南偏东风";
            case "S": return "南风"; case "SSW": return "南偏西风";
            case "SW": return "西南风"; case "WSW": return "西偏南风";
            case "W": return "西风"; case "WNW": return "西偏北风";
            case "NW": return "西北风"; case "NNW": return "北偏西风";
            default: return dir;
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refreshTask);
        handler.post(refreshTask);
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refreshTask);
        rainView.stopRain();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(refreshTask);
        rainView.stopRain();
        executor.shutdownNow();
    }
}
