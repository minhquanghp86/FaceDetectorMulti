package com.example.facedetectormulti.detection;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class MultiFaceDetectorTest {

    @Mock
    private DetectionCallback mockCallback;
    
    private MultiFaceDetector detector;

    @Before
    public void setUp() {
        detector = new MultiFaceDetector(mockCallback);
    }

    @Test
    public void testIsReady_afterInit() {
        assertTrue(detector.isReady());
    }

    @Test
    public void testSetFrameIntervalMs_validValue() {
        detector.setFrameIntervalMs(200);
        // Không có getter, nhưng có thể test gián tiếp qua performance
        // Hoặc thêm getter cho testing purpose
    }

    @Test
    public void testSetFrameIntervalMs_negativeValue() {
        detector.setFrameIntervalMs(-50);
        // Nên được clamp về 0
        // Cần thêm validation trong setter hoặc test behavior
    }

    @Test
    public void testShutdown_setsNotReady() {
        detector.shutdown();
        assertFalse(detector.isReady());
    }

    @Test
    public void testProcess_afterShutdown_doesNotCallCallback() {
        detector.shutdown();
        // Mock ImageProxy và call process
        // Verify callback không được gọi
        // (Cần thêm dependency injection để mock ImageProxy dễ hơn)
    }
}