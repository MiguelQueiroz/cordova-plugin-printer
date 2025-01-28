package de.appplant.cordova.plugin.printer;

import java.io.FileInputStream;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.graphics.pdf.PdfRenderer;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.CancellationSignal;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentAdapter.LayoutResultCallback;
import android.print.PrintJob;
import android.print.PrintManager;
import android.print.PrintDocumentInfo;
import android.print.PageRange;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

@TargetApi(19)
public class Printer extends CordovaPlugin {

    private WebView view;
    private CallbackContext command;
    private static final String DEFAULT_DOC_NAME = "unknown";

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        command = callbackContext;

        if ("isAvailable".equalsIgnoreCase(action)) {
            isAvailable();
            return true;
        }

        if ("print".equalsIgnoreCase(action)) {
            print(args);
            return true;
        }

        return false;
    }

    private void isAvailable() {
        cordova.getThreadPool().execute(() -> {
            boolean supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT;
            PluginResult result = new PluginResult(PluginResult.Status.OK, supported);
            command.sendPluginResult(result);
        });
    }

    private void print(final JSONArray args) {
        final String content = args.optString(0, "<html></html>");
        final JSONObject props = args.optJSONObject(1);

        cordova.getActivity().runOnUiThread(() -> {
            initWebView(props);
            loadContent(content, props);
        });
    }

    private void loadContent(String content, JSONObject props) {
        try {
            if (content.startsWith("http") || content.startsWith("file:")) {
                view.loadUrl(content);
            } else if (content.startsWith("data:application/pdf;base64,")) {
                handlePdfContent(content.substring(28), props);
            } else {
                loadHtmlContent(content);
            }
        } catch (Exception e) {
            sendError("Error loading content: " + e.getMessage());
        }
    }

    private void handlePdfContent(String base64Data, JSONObject props) {
        try {
            File pdfFile = createPdfFile(base64Data);
            renderAndPrintPdf(pdfFile, props);
        } catch (Exception e) {
            sendError("Error processing PDF: " + e.getMessage());
        }
    }

    private File createPdfFile(String base64Data) throws IOException {
        byte[] pdfBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT);
        File pdfFile = new File(cordova.getActivity().getCacheDir(), DEFAULT_DOC_NAME + ".pdf");
        try (FileOutputStream fos = new FileOutputStream(pdfFile)) {
            fos.write(pdfBytes);
        }
        return pdfFile;
    }

    private void loadHtmlContent(String content) {
        String baseURL = webView.getUrl();
        baseURL = baseURL.substring(0, baseURL.lastIndexOf('/') + 1);
        view.loadDataWithBaseURL(baseURL, content, "text/html", "UTF-8", null);
    }

    private void renderAndPrintPdf(File file, JSONObject props) {
        if (!file.exists() || file.length() == 0) {
            sendError("Error: PDF file is empty or does not exist.");
            return;
        }

        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) {
            PrintAttributes attributes = buildPrintAttributes(
                props.optBoolean("landscape", false),
                props.optBoolean("graystyle", false)
            );
            PrintDocumentAdapter adapter = createPdfPrintAdapter(file);
            createPrintJob(file.getName(), adapter, attributes);
        } catch (IOException e) {
            sendError("Error printing PDF: " + e.getMessage());
        }
    }

    private PrintDocumentAdapter createPdfPrintAdapter(File file) {
        return new PrintDocumentAdapter() {
            @Override
            public void onLayout(PrintAttributes oldAttributes, PrintAttributes newAttributes,
                                 CancellationSignal cancellationSignal, LayoutResultCallback callback, Bundle extras) {
                callback.onLayoutFinished(new PrintDocumentInfo.Builder(file.getName())
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .build(), true);
            }

            @Override
            public void onWrite(PageRange[] pages, ParcelFileDescriptor destination,
                                CancellationSignal cancellationSignal, WriteResultCallback callback) {
                try (FileInputStream fis = new FileInputStream(file);
                     FileOutputStream fos = new FileOutputStream(destination.getFileDescriptor())) {

                    byte[] buffer = new byte[1024];
                    int length;
                    while ((length = fis.read(buffer)) > 0) {
                        fos.write(buffer, 0, length);
                    }
                    callback.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
                } catch (IOException e) {
                    sendError("Error writing PDF: " + e.getMessage());
                }
            }
        };
    }

    private void initWebView(JSONObject props) {
        Activity ctx = cordova.getActivity();
        view = new WebView(ctx);
        view.getSettings().setJavaScriptEnabled(true);
        setWebViewClient(props);
    }

    private void setWebViewClient(JSONObject props) {
        final String docName = props != null ? props.optString("name", DEFAULT_DOC_NAME) : DEFAULT_DOC_NAME;
        final boolean landscape = props.optBoolean("landscape", false);
        final boolean graystyle = props.optBoolean("graystyle", false);

        view.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false;
            }

            @Override
            public void onPageFinished(WebView webView, String url) {
                PrintDocumentAdapter printAdapter = webView.createPrintDocumentAdapter(docName);
                PrintAttributes attributes = buildPrintAttributes(landscape, graystyle);
                createPrintJob(docName, printAdapter, attributes);
                view = null; // Release the WebView
            }
        });
    }

    private PrintAttributes buildPrintAttributes(boolean landscape, boolean graystyle) {
        PrintAttributes.Builder builder = new PrintAttributes.Builder();
        builder.setMinMargins(PrintAttributes.Margins.NO_MARGINS);
        builder.setColorMode(graystyle ? PrintAttributes.COLOR_MODE_MONOCHROME : PrintAttributes.COLOR_MODE_COLOR);
        builder.setMediaSize(landscape ? PrintAttributes.MediaSize.UNKNOWN_LANDSCAPE : PrintAttributes.MediaSize.UNKNOWN_PORTRAIT);
        return builder.build();
    }

    private void createPrintJob(String docName, PrintDocumentAdapter adapter, PrintAttributes attributes) {
        PrintManager printManager = (PrintManager) cordova.getActivity().getSystemService(Context.PRINT_SERVICE);
        PrintJob job = printManager.print(docName, adapter, attributes);
        invokeCallbackOnceCompletedOrCanceled(job);

        if (job.isFailed()) {
            sendError("Print job failed.");
        }
    }

    private void invokeCallbackOnceCompletedOrCanceled(final PrintJob job) {
        cordova.getThreadPool().execute(() -> {
            for (;;) {
                if (job.isCancelled() || job.isCompleted() || job.isFailed()) {
                    sendSuccess();
                    break;
                }
            }
        });
    }

    private void sendError(String message) {
        command.error(message);
    }

    private void sendSuccess() {
        command.success();
    }
}
