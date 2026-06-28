package com.example.weatherapp;

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int PERM_REQUEST = 1001;
    private static final long REFRESH_MS = 60_000;

    private Handler handler;
    private Runnable refreshTask;
    private ExecutorService executor;

    private ProgressBar progressBar;
    private View contentView;
    private TextView tvLocation, tvIcon, tvTemp, tvDesc, tvFeelsLike;
    private TextView tvHumidity, tvWind, tvPressure, tvVisibility, tvUpdate;

    private double cachedLat = 0, cachedLon = 0;
    private boolean hasLocation = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        progressBar = findViewById(R.id.progress_bar);
        contentView = findViewById(R.id.content_view);
        tvLocation   = findViewById(R.id.tv_location);
        tvIcon       = findViewById(R.id.tv_icon);
        tvTemp       = findViewById(R.id.tv_temp);
        tvDesc       = findViewById(R.id.tv_desc);
        tvFeelsLike  = findViewById(R.id.tv_feels_like);
        tvHumidity   = findViewById(R.id.tv_humidity);
        tvWind       = findViewById(R.id.tv_wind);
        tvPressure   = findViewById(R.id.tv_pressure);
        tvVisibility = findViewById(R.id.tv_visibility);
        tvUpdate     = findViewById(R.id.tv_update);

        executor = Executors.newSingleThreadExecutor();
        handler  = new Handler(Looper.getMainLooper());

        refreshTask = () -> {
            resolveLocationAndFetch();
            handler.postDelayed(refreshTask, REFRESH_MS);
        };

        checkPermissions();
    }

    private void checkPermissions() {
        boolean fine = ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (fine || coarse) {
            startRefreshing();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    PERM_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        startRefreshing();
    }

    private void startRefreshing() {
        handler.removeCallbacks(refreshTask);
        handler.post(refreshTask);
    }

    private void resolveLocationAndFetch() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {

            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            Location loc = null;
            try {
                loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (loc == null) {
                    loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                }
                if (loc == null) {
                    loc = lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
                }
            } catch (Exception ignored) {}

            if (loc != null) {
                cachedLat = loc.getLatitude();
                cachedLon = loc.getLongitude();
                hasLocation = true;
            }
        }
        fetchWeather();
    }

    private void fetchWeather() {
        executor.execute(() -> {
            try {
                String query;
                if (hasLocation) {
                    query = String.format(Locale.US, "%.4f,%.4f", cachedLat, cachedLon);
                } else {
                    query = "";   // wttr.in auto-detects by IP
                }
                String endpoint = "https://wttr.in/" + query + "?format=j1";
                URL url = new URL(endpoint);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(15_000);
                conn.setRequestProperty("User-Agent", "WeatherApp-Android/1.0");

                if (conn.getResponseCode() != 200) {
                    showError("服务暂时不可用 (" + conn.getResponseCode() + ")");
                    conn.disconnect();
                    return;
                }

                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
                conn.disconnect();
                parseAndShow(sb.toString());

            } catch (Exception e) {
                showError("网络错误，稍后重试");
            }
        });
    }

    private void parseAndShow(String json) {
        try {
            JSONObject root     = new JSONObject(json);
            JSONObject cur      = root.getJSONArray("current_condition").getJSONObject(0);
            JSONObject area     = root.getJSONArray("nearest_area").getJSONObject(0);

            int    tempC      = Integer.parseInt(cur.getString("temp_C"));
            int    feelsLike  = Integer.parseInt(cur.getString("FeelsLikeC"));
            int    humidity   = Integer.parseInt(cur.getString("humidity"));
            int    windKmph   = Integer.parseInt(cur.getString("windspeedKmph"));
            String windDir    = cur.getString("winddir16Point");
            int    pressure   = Integer.parseInt(cur.getString("pressure"));
            int    visibility = Integer.parseInt(cur.getString("visibility"));
            int    code       = Integer.parseInt(cur.getString("weatherCode"));
            String descEn     = cur.getJSONArray("weatherDesc").getJSONObject(0).getString("value");

            String areaName = area.getJSONArray("areaName").getJSONObject(0).getString("value");
            String country  = area.getJSONArray("country").getJSONObject(0).getString("value");
            String location = areaName.isEmpty() ? country : areaName + ", " + country;

            String icon     = codeToEmoji(code);
            String descZh   = codeToZh(code, descEn);
            String windDirZh = windDirToZh(windDir);
            String timeStr  = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());

            runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);
                contentView.setVisibility(View.VISIBLE);

                tvLocation.setText("📍 " + location);
                tvIcon.setText(icon);
                tvTemp.setText(tempC + "°C");
                tvDesc.setText(descZh);
                tvFeelsLike.setText("体感 " + feelsLike + "°C");
                tvHumidity.setText("💧  " + humidity + "%");
                tvWind.setText("💨  " + windKmph + " km/h " + windDirZh);
                tvPressure.setText("🌡  " + pressure + " hPa");
                tvVisibility.setText("👁  " + visibility + " km");
                tvUpdate.setText("⏱ 上次更新：" + timeStr + "  (每分钟自动刷新)");
            });

        } catch (Exception e) {
            showError("数据解析失败: " + e.getMessage());
        }
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
            case 263: return "毛毛雨";
            case 266: return "毛毛雨";
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
            case "N":   return "北风";
            case "NNE": return "北偏东风";
            case "NE":  return "东北风";
            case "ENE": return "东偏北风";
            case "E":   return "东风";
            case "ESE": return "东偏南风";
            case "SE":  return "东南风";
            case "SSE": return "南偏东风";
            case "S":   return "南风";
            case "SSW": return "南偏西风";
            case "SW":  return "西南风";
            case "WSW": return "西偏南风";
            case "W":   return "西风";
            case "WNW": return "西偏北风";
            case "NW":  return "西北风";
            case "NNW": return "北偏西风";
            default:    return dir;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refreshTask);
        handler.post(refreshTask);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refreshTask);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(refreshTask);
        executor.shutdownNow();
    }
}
