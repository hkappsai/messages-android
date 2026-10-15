package co.hkapps.messages;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes MMS messages in their binary wire format (OMA MMS encapsulation over
 * WAP WSP encoding). Plain Java with no Android imports, so it can be unit-tested anywhere.
 */
public final class MmsPdu {

    // Message types (X-Mms-Message-Type values).
    public static final int TYPE_SEND_REQ = 0x80;
    public static final int TYPE_SEND_CONF = 0x81;
    public static final int TYPE_NOTIFICATION_IND = 0x82;
    public static final int TYPE_NOTIFYRESP_IND = 0x83;
    public static final int TYPE_RETRIEVE_CONF = 0x84;
    public static final int TYPE_DELIVERY_IND = 0x86;
    /** X-Mms-Status values in a delivery report. */
    public static final int STATUS_EXPIRED = 0x80;
    public static final int STATUS_RETRIEVED = 0x81;
    public static final int STATUS_REJECTED = 0x82;

    // Header field codes, already OR-ed with 0x80.
    private static final int H_BCC = 0x81;
    private static final int H_CC = 0x82;
    private static final int H_CONTENT_LOCATION = 0x83;
    private static final int H_CONTENT_TYPE = 0x84;
    private static final int H_DATE = 0x85;
    private static final int H_DELIVERY_REPORT = 0x86;
    private static final int H_FROM = 0x89;
    private static final int H_MESSAGE_CLASS = 0x8A;
    private static final int H_MESSAGE_ID = 0x8B;
    private static final int H_MESSAGE_TYPE = 0x8C;
    private static final int H_MMS_VERSION = 0x8D;
    private static final int H_MESSAGE_SIZE = 0x8E;
    private static final int H_PRIORITY = 0x8F;
    private static final int H_READ_REPORT = 0x90;
    private static final int H_RESPONSE_STATUS = 0x92;
    private static final int H_STATUS = 0x95;
    private static final int H_SUBJECT = 0x96;
    private static final int H_TO = 0x97;
    private static final int H_TRANSACTION_ID = 0x98;

    // Part header codes (WSP well-known header fields, OR-ed with 0x80).
    private static final int PH_CONTENT_LOCATION = 0x8E;
    private static final int PH_CONTENT_ID = 0xC0;

    // Content-type parameter codes (OR-ed with 0x80).
    private static final int P_CHARSET = 0x81;
    private static final int P_NAME_OLD = 0x85;
    private static final int P_TYPE = 0x89;
    private static final int P_START = 0x8A;
    private static final int P_NAME = 0x97;

    private static final int CHARSET_UTF8 = 106;
    private static final int MMS_VERSION_1_2 = 0x92;

    /** WSP well-known content types; the index is the type's code. */
    private static final String[] CONTENT_TYPES = {
            "*/*", "text/*", "text/html", "text/plain", "text/x-hdml", "text/x-ttml",
            "text/x-vCalendar", "text/x-vCard", "text/vnd.wap.wml", "text/vnd.wap.wmlscript",
            "text/vnd.wap.wta-event", "multipart/*", "multipart/mixed", "multipart/form-data",
            "multipart/byterantes", "multipart/alternative", "application/*", "application/java-vm",
            "application/x-www-form-urlencoded", "application/x-hdmlc", "application/vnd.wap.wmlc",
            "application/vnd.wap.wmlscriptc", "application/vnd.wap.wta-eventc",
            "application/vnd.wap.uaprof", "application/vnd.wap.wtls-ca-certificate",
            "application/vnd.wap.wtls-user-certificate", "application/x-x509-ca-cert",
            "application/x-x509-user-cert", "image/*", "image/gif", "image/jpeg", "image/tiff",
            "image/png", "image/vnd.wap.wbmp", "application/vnd.wap.multipart.*",
            "application/vnd.wap.multipart.mixed", "application/vnd.wap.multipart.form-data",
            "application/vnd.wap.multipart.byteranges", "application/vnd.wap.multipart.alternative",
            "application/xml", "text/xml", "application/vnd.wap.wbxml",
            "application/x-x968-cross-cert", "application/x-x968-ca-cert",
            "application/x-x968-user-cert", "text/vnd.wap.si", "application/vnd.wap.sic",
            "text/vnd.wap.sl", "application/vnd.wap.slc", "text/vnd.wap.co",
            "application/vnd.wap.coc", "application/vnd.wap.multipart.related",
            "application/vnd.wap.sia", "text/vnd.wap.connectivity-xml",
            "application/vnd.wap.connectivity-wbxml", "application/pkcs7-mime",
            "application/vnd.wap.hashed-certificate", "application/vnd.wap.signed-certificate",
            "application/vnd.wap.cert-response", "application/xhtml+xml", "application/wml+xml",
            "text/css", "application/vnd.wap.mms-message",
    };

    public static final String MULTIPART_RELATED = "application/vnd.wap.multipart.related";
    public static final String MULTIPART_MIXED = "application/vnd.wap.multipart.mixed";

    private MmsPdu() {}

    /** One piece of an MMS: text, SMIL layout, picture, audio and so on. */
    public static final class Part {
        public String contentType;
        public String name;
        public String contentId;
        public String contentLocation;
        public int charset;
        public byte[] data;

        public Part() {}

        public Part(String contentType, String name, byte[] data) {
            this.contentType = contentType;
            this.name = name;
            this.contentLocation = name;
            this.contentId = "<" + name + ">";
            this.data = data;
        }

        /** Text content, decoded with the part's charset. */
        public String text() {
            return new String(data, charsetFor(charset));
        }
    }

    /** The headers and parts this app cares about. */
    public static final class Message {
        public int type;
        public String transactionId;
        public String messageId;
        public String contentLocation;
        public String from;
        public final List<String> to = new ArrayList<>();
        public final List<String> cc = new ArrayList<>();
        public String subject;
        public long date;
        public long size;
        public int responseStatus;
        /** X-Mms-Status of a delivery report. */
        public int status;
        public String contentType;
        public final List<Part> parts = new ArrayList<>();
    }

    // ---------------------------------------------------------------- writing

    /** Builds an m-send-req: the message handed to the carrier for sending. */
    public static byte[] encodeSendReq(String transactionId, List<String> recipients, String subject,
                                       List<Part> parts, long dateSeconds) {
        return encodeSendReq(transactionId, recipients, subject, parts, dateSeconds, false);
    }

    /** With deliveryReport, the carrier sends an m-delivery-ind once each person gets it. */
    public static byte[] encodeSendReq(String transactionId, List<String> recipients, String subject,
                                       List<Part> parts, long dateSeconds, boolean deliveryReport) {
        Writer w = new Writer();
        w.octet(H_MESSAGE_TYPE).octet(TYPE_SEND_REQ);
        w.octet(H_TRANSACTION_ID).text(transactionId);
        w.octet(H_MMS_VERSION).octet(MMS_VERSION_1_2);
        w.octet(H_DATE).longInteger(dateSeconds);
        // From: "insert address" lets the carrier fill in our own number.
        w.octet(H_FROM).octet(1).octet(0x81);
        for (String to : recipients) {
            w.octet(H_TO).text(toWireAddress(to));
        }
        if (subject != null && !subject.isEmpty()) {
            w.octet(H_SUBJECT).encodedString(subject);
        }
        w.octet(H_MESSAGE_CLASS).octet(0x80);     // personal
        w.octet(H_PRIORITY).octet(0x81);          // normal
        w.octet(H_DELIVERY_REPORT).octet(deliveryReport ? 0x80 : 0x81);
        w.octet(H_READ_REPORT).octet(0x81);       // no

        // Content-Type: multipart/related, pointing at the SMIL part when there is one.
        Part smil = null;
        for (Part p : parts) {
            if ("application/smil".equals(p.contentType)) smil = p;
        }
        Writer ct = new Writer();
        ct.mediaType(smil != null ? MULTIPART_RELATED : MULTIPART_MIXED);
        if (smil != null) {
            ct.octet(P_START).text(smil.contentId);
            ct.octet(P_TYPE).text("application/smil");
        }
        w.octet(H_CONTENT_TYPE).valueLength(ct.size()).bytes(ct.toByteArray());

        w.multipart(parts);
        return w.toByteArray();
    }

    /** Builds an m-notifyresp-ind, telling the carrier a message was fetched. */
    public static byte[] encodeNotifyResp(String transactionId) {
        Writer w = new Writer();
        w.octet(H_MESSAGE_TYPE).octet(TYPE_NOTIFYRESP_IND);
        w.octet(H_TRANSACTION_ID).text(transactionId);
        w.octet(H_MMS_VERSION).octet(MMS_VERSION_1_2);
        w.octet(H_STATUS).octet(0x81);  // retrieved
        return w.toByteArray();
    }

    /** "+1 (555) 010-0100" becomes "+15550100100/TYPE=PLMN"; emails stay as they are. */
    static String toWireAddress(String address) {
        if (address.contains("@")) return address;
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < address.length(); i++) {
            char c = address.charAt(i);
            if (Character.isDigit(c) || (c == '+' && digits.length() == 0)) digits.append(c);
        }
        return digits + "/TYPE=PLMN";
    }

    /** "+15550100100/TYPE=PLMN" becomes "+15550100100". */
    static String fromWireAddress(String address) {
        if (address == null) return null;
        int slash = address.indexOf("/TYPE=");
        return (slash >= 0 ? address.substring(0, slash) : address).trim();
    }

    private static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Writer octet(int b) { out.write(b & 0xFF); return this; }

        Writer bytes(byte[] b) { out.write(b, 0, b.length); return this; }

        int size() { return out.size(); }

        byte[] toByteArray() { return out.toByteArray(); }

        Writer uintvar(long value) {
            byte[] tmp = new byte[10];
            int n = 0;
            do {
                tmp[n++] = (byte) (value & 0x7F);
                value >>>= 7;
            } while (value != 0);
            for (int i = n - 1; i >= 0; i--) octet(tmp[i] | (i > 0 ? 0x80 : 0));
            return this;
        }

        Writer valueLength(int length) {
            if (length <= 30) return octet(length);
            return octet(31).uintvar(length);
        }

        Writer longInteger(long value) {
            byte[] tmp = new byte[8];
            int n = 0;
            do {
                tmp[n++] = (byte) (value & 0xFF);
                value >>>= 8;
            } while (value != 0);
            octet(n);
            for (int i = n - 1; i >= 0; i--) octet(tmp[i]);
            return this;
        }

        Writer text(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            if (b.length > 0 && (b[0] & 0xFF) >= 0x80) octet(0x7F);
            return bytes(b).octet(0);
        }

        Writer quoted(String s) {
            return octet(0x22).bytes(s.getBytes(StandardCharsets.UTF_8)).octet(0);
        }

        Writer encodedString(String s) {
            Writer inner = new Writer();
            inner.octet(0x80 | CHARSET_UTF8).text(s);
            return valueLength(inner.size()).bytes(inner.toByteArray());
        }

        Writer mediaType(String type) {
            for (int i = 0; i < CONTENT_TYPES.length; i++) {
                if (CONTENT_TYPES[i].equalsIgnoreCase(type)) return octet(0x80 | i);
            }
            return text(type);
        }

        Writer multipart(List<Part> parts) {
            uintvar(parts.size());
            for (Part p : parts) {
                Writer headers = new Writer();
                Writer ct = new Writer();
                ct.mediaType(p.contentType);
                if (p.contentType.startsWith("text/") || "application/smil".equals(p.contentType)) {
                    ct.octet(P_CHARSET).octet(0x80 | CHARSET_UTF8);
                }
                if (p.name != null) ct.octet(P_NAME_OLD).text(p.name);
                headers.valueLength(ct.size()).bytes(ct.toByteArray());
                if (p.contentId != null) headers.octet(PH_CONTENT_ID).quoted(p.contentId);
                if (p.contentLocation != null) headers.octet(PH_CONTENT_LOCATION).text(p.contentLocation);

                byte[] h = headers.toByteArray();
                uintvar(h.length);
                uintvar(p.data.length);
                bytes(h);
                bytes(p.data);
            }
            return this;
        }
    }

    // ---------------------------------------------------------------- reading

    /** Reads any MMS PDU. Throws IllegalArgumentException when the data is malformed. */
    public static Message parse(byte[] pdu) {
        Reader r = new Reader(pdu);
        Message m = new Message();
        try {
            while (r.hasMore()) {
                int field = r.peek();
                if (field < 0x80) {
                    // Application header: "Name" "Value", both plain text.
                    r.text();
                    r.text();
                    continue;
                }
                r.next();
                if (field == H_CONTENT_TYPE) {
                    ContentType ct = r.contentType();
                    m.contentType = ct.type;
                    if (ct.type.startsWith("application/vnd.wap.multipart")) {
                        r.multipart(m.parts);
                    } else if (r.hasMore()) {
                        // A single-part message: the rest of the PDU is the content.
                        Part p = new Part();
                        p.contentType = ct.type;
                        p.charset = ct.charset;
                        p.name = ct.name;
                        p.data = r.rest();
                        m.parts.add(p);
                    }
                    break;
                }
                switch (field) {
                    case H_MESSAGE_TYPE: m.type = r.next(); break;
                    case H_TRANSACTION_ID: m.transactionId = r.text(); break;
                    case H_MESSAGE_ID: m.messageId = r.text(); break;
                    case H_CONTENT_LOCATION: m.contentLocation = r.text(); break;
                    case H_FROM: m.from = r.fromValue(); break;
                    case H_TO: m.to.add(fromWireAddress(r.encodedString())); break;
                    case H_CC: m.cc.add(fromWireAddress(r.encodedString())); break;
                    case H_BCC: r.encodedString(); break;
                    case H_SUBJECT: m.subject = r.encodedString(); break;
                    case H_DATE: m.date = r.longInteger(); break;
                    case H_MESSAGE_SIZE: m.size = r.longInteger(); break;
                    case H_RESPONSE_STATUS: m.responseStatus = r.next(); break;
                    case H_STATUS: m.status = r.next(); break;
                    default: r.skipValue(); break;
                }
            }
        } catch (IndexOutOfBoundsException e) {
            throw new IllegalArgumentException("Truncated MMS PDU", e);
        }
        return m;
    }

    private static final class ContentType {
        String type = "application/octet-stream";
        int charset;
        String name;
    }

    private static final class Reader {
        private final byte[] b;
        private int pos;

        Reader(byte[] b) { this.b = b; }

        boolean hasMore() { return pos < b.length; }

        int peek() { return b[pos] & 0xFF; }

        int next() { return b[pos++] & 0xFF; }

        byte[] take(int n) {
            if (n < 0 || pos + n > b.length) throw new IndexOutOfBoundsException();
            byte[] out = new byte[n];
            System.arraycopy(b, pos, out, 0, n);
            pos += n;
            return out;
        }

        byte[] rest() { return take(b.length - pos); }

        long uintvar() {
            long v = 0;
            for (int i = 0; i < 5; i++) {
                int x = next();
                v = (v << 7) | (x & 0x7F);
                if ((x & 0x80) == 0) return v;
            }
            throw new IllegalArgumentException("Bad uintvar");
        }

        /** Short-length (0-30) or Length-quote (31) + uintvar. */
        int valueLength() {
            int x = next();
            if (x <= 30) return x;
            if (x == 31) return (int) uintvar();
            throw new IllegalArgumentException("Bad value length " + x);
        }

        String text() {
            int start = pos;
            if (peek() == 0x7F || peek() == 0x22) start = ++pos;
            while (b[pos] != 0) pos++;
            String s = new String(b, start, pos - start, StandardCharsets.UTF_8);
            pos++;  // NUL
            return s;
        }

        long longInteger() {
            int x = next();
            if (x >= 0x80) return x & 0x7F;  // Short-integer
            long v = 0;
            for (int i = 0; i < x; i++) v = (v << 8) | next();
            return v;
        }

        /** Text-string, or Value-length Charset Text-string. */
        String encodedString() {
            int x = peek();
            if (x > 31) return text();
            int len = valueLength();
            int end = pos + len;
            int charset = (int) integer();
            int start = pos;
            if (pos < end && peek() == 0x7F) start = ++pos;
            int stop = start;
            while (stop < end && b[stop] != 0) stop++;
            String s = new String(b, start, stop - start, charsetFor(charset));
            pos = end;
            return s;
        }

        /** Short-integer or Long-integer. */
        long integer() {
            return peek() >= 0x80 ? next() & 0x7F : longInteger();
        }

        /** From: Value-length (Address-present Encoded-string | Insert-address). */
        String fromValue() {
            int len = valueLength();
            int end = pos + len;
            String from = null;
            if (len > 0 && next() == 0x80 && pos < end) from = fromWireAddress(encodedString());
            pos = end;
            return from;
        }

        /** Skips any header value using the generic WSP rules. */
        void skipValue() {
            int x = peek();
            if (x <= 30) { pos++; pos += x; }
            else if (x == 31) { pos++; pos += (int) uintvar(); }
            else if (x < 0x80) text();
            else pos++;
        }

        ContentType contentType() {
            ContentType ct = new ContentType();
            int x = peek();
            if (x >= 0x80) {               // Constrained: well-known short-integer
                ct.type = typeName(next() & 0x7F);
                return ct;
            }
            if (x > 31) {                  // Constrained: extension media text
                ct.type = text();
                return ct;
            }
            int len = valueLength();       // General form with parameters
            int end = pos + len;
            if (peek() >= 0x80) ct.type = typeName(next() & 0x7F);
            else if (peek() > 31) ct.type = text();
            else ct.type = typeName((int) longInteger());
            while (pos < end) {
                int p = peek();
                if (p >= 0x80) {
                    next();
                    switch (p) {
                        case P_CHARSET: ct.charset = (int) paramInteger(); break;
                        case P_NAME_OLD:
                        case P_NAME: ct.name = text(); break;
                        default: skipValue(); break;
                    }
                } else {
                    String name = text();  // Untyped parameter: Token-text Untyped-value
                    if (peek() > 31 && peek() < 0x80) {
                        String v = text();
                        if ("name".equalsIgnoreCase(name)) ct.name = v;
                    } else {
                        skipValue();
                    }
                }
            }
            pos = end;
            return ct;
        }

        long paramInteger() {
            int x = peek();
            if (x >= 0x80) return next() & 0x7F;
            if (x <= 30) return longInteger();
            text();
            return 0;
        }

        void multipart(List<Part> parts) {
            long count = uintvar();
            for (long i = 0; i < count && hasMore(); i++) {
                int headersLen = (int) uintvar();
                int dataLen = (int) uintvar();
                int headersEnd = pos + headersLen;

                Part part = new Part();
                ContentType ct = contentType();
                part.contentType = ct.type;
                part.charset = ct.charset;
                part.name = ct.name;
                while (pos < headersEnd) {
                    int field = peek();
                    if (field >= 0x80) {
                        next();
                        if (field == PH_CONTENT_LOCATION) part.contentLocation = text();
                        else if (field == PH_CONTENT_ID) part.contentId = text();
                        else skipValue();
                    } else {
                        text();
                        text();
                    }
                }
                pos = headersEnd;
                part.data = take(dataLen);
                if (part.name == null) part.name = part.contentLocation;
                parts.add(part);
            }
        }

        private static String typeName(int code) {
            return code < CONTENT_TYPES.length ? CONTENT_TYPES[code] : "application/octet-stream";
        }
    }

    static Charset charsetFor(int mib) {
        switch (mib) {
            case 3: return StandardCharsets.US_ASCII;
            case 4: return StandardCharsets.ISO_8859_1;
            case 1000: return Charset.forName("UTF-16BE");  // ISO-10646-UCS-2
            case 1013: return StandardCharsets.UTF_16BE;
            case 1014: return StandardCharsets.UTF_16LE;
            case 1015: return StandardCharsets.UTF_16;
            case 17: return Charset.forName("Shift_JIS");
            case 2026: return Charset.forName("Big5");
            default: return StandardCharsets.UTF_8;
        }
    }
}
