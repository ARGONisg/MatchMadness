package com.example.matchbot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MatchMadnessService extends AccessibilityService {

    private static final String TAG = "MatchBot";
    private final Map<String, String> dictionary = new HashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isProcessing = false;

    @Override
    public void onServiceConnected() {
        Log.d(TAG, "Service connected.");
        // Seed dictionary with words from the screenshot
        dictionary.put("chinese", "chino");
        dictionary.put("to start", "empezar");
        dictionary.put("same", "mismo");
        dictionary.put("june", "junio");
        dictionary.put("january", "enero");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (isProcessing) return;

        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return;

        List<AccessibilityNodeInfo> buttons = new ArrayList<>();
        findButtons(rootNode, buttons);

        if (buttons.size() >= 4 && buttons.size() <= 10) {
            isProcessing = true;
            processBoard(buttons);
        }
    }

    private void findButtons(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> buttons) {
        if (node.isClickable() && node.getText() != null && node.getText().length() > 0) {
            buttons.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                findButtons(child, buttons);
            }
        }
    }

    private void processBoard(List<AccessibilityNodeInfo> buttons) {
        // Sort into left and right columns dynamically by X coordinate
        Collections.sort(buttons, new Comparator<AccessibilityNodeInfo>() {
            @Override
            public int compare(AccessibilityNodeInfo n1, AccessibilityNodeInfo n2) {
                Rect r1 = new Rect();
                n1.getBoundsInScreen(r1);
                Rect r2 = new Rect();
                n2.getBoundsInScreen(r2);
                return Integer.compare(r1.centerX(), r2.centerX());
            }
        });

        List<AccessibilityNodeInfo> leftColumn = new ArrayList<>();
        List<AccessibilityNodeInfo> rightColumn = new ArrayList<>();
        int mid = buttons.size() / 2;
        for (int i = 0; i < buttons.size(); i++) {
            if (i < mid) {
                leftColumn.add(buttons.get(i));
            } else {
                rightColumn.add(buttons.get(i));
            }
        }

        // Run matching in background to avoid blocking accessibility thread if we need API
        executor.execute(() -> {
            boolean clicked = false;
            for (AccessibilityNodeInfo leftBtn : leftColumn) {
                String englishWord = leftBtn.getText().toString().toLowerCase().trim();
                
                String expectedSpanish = dictionary.get(englishWord);
                if (expectedSpanish == null) {
                    expectedSpanish = translateEnglishToSpanish(englishWord);
                    if (expectedSpanish != null) {
                        dictionary.put(englishWord, expectedSpanish);
                    }
                }

                if (expectedSpanish != null) {
                    for (AccessibilityNodeInfo rightBtn : rightColumn) {
                        String spanishWord = rightBtn.getText().toString().toLowerCase().trim();
                        // Loose matching for Duolingo variations
                        if (spanishWord.contains(expectedSpanish) || expectedSpanish.contains(spanishWord) || isSimilar(expectedSpanish, spanishWord)) {
                            clickNodes(leftBtn, rightBtn);
                            clicked = true;
                            break;
                        }
                    }
                }
                if (clicked) break; // Only do one pair at a time to let UI update
            }
            handler.postDelayed(() -> isProcessing = false, 150);
        });
    }

    private void clickNodes(AccessibilityNodeInfo node1, AccessibilityNodeInfo node2) {
        Rect bounds1 = new Rect();
        node1.getBoundsInScreen(bounds1);
        Rect bounds2 = new Rect();
        node2.getBoundsInScreen(bounds2);

        Path path1 = new Path();
        path1.moveTo(bounds1.centerX(), bounds1.centerY());
        
        Path path2 = new Path();
        path2.moveTo(bounds2.centerX(), bounds2.centerY());

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path1, 0, 50));
        builder.addStroke(new GestureDescription.StrokeDescription(path2, 0, 50));
        
        dispatchGesture(builder.build(), null, null);
    }

    private String translateEnglishToSpanish(String text) {
        try {
            String urlStr = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=es&dt=t&q=" + java.net.URLEncoder.encode(text, "UTF-8");
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            String inputLine;
            StringBuilder response = new StringBuilder();
            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            in.close();
            
            // Response format: [[["hola","hello",null,null,1]],null,"en",...
            JSONArray jsonArray = new JSONArray(response.toString());
            JSONArray part1 = jsonArray.getJSONArray(0);
            JSONArray part2 = part1.getJSONArray(0);
            return part2.getString(0).toLowerCase().trim();
        } catch (Exception e) {
            Log.e(TAG, "Translation error", e);
            return null;
        }
    }
    
    private boolean isSimilar(String s1, String s2) {
        // Simple Levenshtein or just length check. 
        // For speed, just check if they start with the same 4 letters (many spanish verbs differ in conjugation)
        if (s1.length() >= 4 && s2.length() >= 4) {
            return s1.substring(0, 4).equals(s2.substring(0, 4));
        }
        return false;
    }

    @Override
    public void onInterrupt() {
    }
}
