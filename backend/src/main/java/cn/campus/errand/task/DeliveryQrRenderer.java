package cn.campus.errand.task;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

@Component
class DeliveryQrRenderer {
    private static final int SIZE = 256;

    byte[] render(String payload) {
        try (var png = new ByteArrayOutputStream()) {
            BitMatrix code = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, SIZE, SIZE);
            var image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_BYTE_BINARY);
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    image.setRGB(x, y, code.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
                }
            }
            if (!ImageIO.write(image, "png", png)) throw new IOException("PNG 编码器不可用");
            return png.toByteArray();
        } catch (IOException | WriterException e) {
            throw new IllegalStateException("交付码图片生成失败", e);
        }
    }
}
