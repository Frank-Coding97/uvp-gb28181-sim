#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

/** Native Filament/Metal view used by the Compose iOS 3D stage. */
@interface UVPFilamentView : UIView

- (void)stopRendering;

- (void)setPanAngle:(float)pan
         tiltAngle:(float)tilt
         zoomLevel:(float)zoom
           duration:(double)duration;

- (void)setPanSpeed:(float)panSpeed
          tiltSpeed:(float)tiltSpeed
          zoomSpeed:(float)zoomSpeed;

- (float)currentPanAngle;
- (float)currentTiltAngle;
- (float)currentZoomLevel;

- (void)easeToPanAngle:(float)pan
            tiltAngle:(float)tilt
            zoomLevel:(float)zoom
             duration:(double)duration;

- (void)triggerIFrameFlash;
- (void)triggerSnapshotFlash;
- (void)triggerRebootAnimation;

@end

NS_ASSUME_NONNULL_END
