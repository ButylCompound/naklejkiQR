package pl.almara.inwentaryzacja;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;

public class PdfShareReceiverActivity extends Activity {
    public static final String RESULT_ACTION = "pl.almara.inwentaryzacja.PDF_SHARE_TEST_RESULT";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Intent result = new Intent(RESULT_ACTION).setPackage(getIntent().getStringExtra("replyPackage"));
        try {
            Uri uri = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
            byte[] header = new byte[5];
            try (DataInputStream stream = new DataInputStream(getContentResolver().openInputStream(uri))) {
                stream.readFully(header);
            }
            result.putExtra("signature", new String(header, StandardCharsets.US_ASCII));
            result.putExtra("subject", getIntent().getStringExtra(Intent.EXTRA_SUBJECT));
            result.putExtra("clipMatches", uri.equals(getIntent().getClipData().getItemAt(0).getUri()));
            result.putExtra("receiverUid", Process.myUid());
        } catch (Exception error) {
            result.putExtra("error", error.toString());
        }
        sendBroadcast(result);
        finish();
    }
}
