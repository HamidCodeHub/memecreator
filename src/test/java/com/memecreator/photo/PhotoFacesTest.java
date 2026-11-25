package com.memecreator.photo;

import com.memecreator.face.DetectedFace;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class PhotoFacesTest {

    private static final BufferedImage PHOTO = new BufferedImage(1000, 2000, BufferedImage.TYPE_INT_RGB);

    @Test
    void growsEachFaceToTheWholeHead() {
        // face box 100x200 at (500, 400) on a 1000x2000 photo
        DetectedFace face = new DetectedFace(500, 400, 100, 200, new double[10], 0.56);
        List<KeepClearArea> heads = new PhotoFaces(image -> List.of(face)).heads(PHOTO);

        KeepClearArea head = heads.get(0);
        assertThat(head.source()).isEqualTo("face");
        assertThat(head.x()).isCloseTo((500 - 35) / 1000.0, offset(1e-9));
        assertThat(head.y()).isCloseTo((400 - 120) / 2000.0, offset(1e-9));   // forehead and hair above the box
        assertThat(head.width()).isCloseTo(170 / 1000.0, offset(1e-9));
        assertThat(head.bottom()).isCloseTo((400 - 120 + 350) / 2000.0, offset(1e-9));
    }

    @Test
    void aBrokenDetectorFindsNothingInsteadOfFailingTheMemes() {
        PhotoFaces faces = new PhotoFaces(image -> {
            throw new UnsatisfiedLinkError("no opencv_java in java.library.path");
        });
        assertThat(faces.heads(PHOTO)).isEmpty();
        assertThat(faces.heads(PHOTO)).isEmpty();   // and doesn't try again
    }
}
