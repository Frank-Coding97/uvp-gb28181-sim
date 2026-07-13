#import "UVPFilamentView.h"

#import <QuartzCore/CADisplayLink.h>
#import <Metal/Metal.h>

#include <algorithm>
#include <cmath>
#include <vector>

#include <filament/Camera.h>
#include <filament/Engine.h>
#include <filament/IndirectLight.h>
#include <filament/LightManager.h>
#include <filament/Renderer.h>
#include <filament/RenderableManager.h>
#include <filament/Scene.h>
#include <filament/TransformManager.h>
#include <filament/View.h>
#include <filament/Viewport.h>

#include <gltfio/AssetLoader.h>
#include <gltfio/FilamentAsset.h>
#include <gltfio/ResourceLoader.h>
#include <gltfio/TextureProvider.h>
#include <gltfio/materials/uberarchive.h>

#include <math/mat4.h>
#include <utils/EntityManager.h>
#include <utils/NameComponentManager.h>

using namespace filament;
using namespace filament::gltfio;
using namespace filament::math;
using namespace utils;

namespace {
constexpr double kNear = 0.05;
constexpr double kFar = 1000.0;
constexpr float kDefaultFov = 60.0f;
constexpr float kDegreesToRadians = 0.017453292519943295f;
// Neutral blue-grey keeps the white camera body and black lens readable while
// preserving the existing cool iOS visual language.
constexpr double4 kBackgroundColor = {0.149, 0.196, 0.220, 1.0};
}

@interface UVPFilamentView () {
    Engine* _engine;
    Renderer* _renderer;
    Scene* _scene;
    filament::View* _filamentView;
    SwapChain* _swapChain;
    Camera* _camera;
    Entity _cameraEntity;
    Entity _lightEntity;
    Entity _yawPivot;
    Entity _pitchPivot;
    mat4f _yawInitial;
    mat4f _pitchInitial;
    MaterialProvider* _materialProvider;
    AssetLoader* _assetLoader;
    ResourceLoader* _resourceLoader;
    TextureProvider* _stbDecoder;
    TextureProvider* _ktxDecoder;
    TextureProvider* _webpDecoder;
    FilamentAsset* _asset;
    IndirectLight* _indirectLight;
    CADisplayLink* _displayLink;
    float _pan;
    float _tilt;
    float _zoom;
    float _fromPan;
    float _fromTilt;
    float _fromZoom;
    float _toPan;
    float _toTilt;
    float _toZoom;
    float _panSpeed;
    float _tiltSpeed;
    float _zoomSpeed;
    double _animationStart;
    double _animationDuration;
    double _flashUntil;
    double _lastFrameTime;
    double _selfTestStart;
    BOOL _selfTestActive;
}
- (float)easedValueFrom:(float)from to:(float)to phase:(double)phase;
- (void)updateProjection;
@end

@implementation UVPFilamentView

+ (Class)layerClass {
    return [CAMetalLayer class];
}

- (instancetype)initWithFrame:(CGRect)frame {
    self = [super initWithFrame:frame];
    if (self) {
        [self initializeFilament];
    }
    return self;
}

- (void)initializeFilament {
    self.backgroundColor = [UIColor colorWithRed:0.149 green:0.196 blue:0.220 alpha:1.0];
    self.contentScaleFactor = UIScreen.mainScreen.nativeScale;
    CAMetalLayer* metalLayer = (CAMetalLayer*)self.layer;
    metalLayer.device = MTLCreateSystemDefaultDevice();
    metalLayer.pixelFormat = MTLPixelFormatBGRA8Unorm;
    metalLayer.opaque = YES;

    _engine = Engine::create(Engine::Backend::METAL);
    if (!_engine) {
        NSLog(@"[Filament] engine create failed (backend=Metal)");
        return;
    }
    _renderer = _engine->createRenderer();
    _scene = _engine->createScene();
    _cameraEntity = EntityManager::get().create();
    _camera = _engine->createCamera(_cameraEntity);
    _filamentView = _engine->createView();
    _filamentView->setScene(_scene);
    _filamentView->setCamera(_camera);
    _filamentView->setAntiAliasing(View::AntiAliasing::NONE);
    _renderer->setClearOptions({.clear = true, .clearColor = kBackgroundColor});

    _swapChain = _engine->createSwapChain((__bridge void*)self.layer);
    _materialProvider = createUbershaderProvider(
            _engine, UBERARCHIVE_DEFAULT_DATA, UBERARCHIVE_DEFAULT_SIZE);

    EntityManager& entities = EntityManager::get();
    auto* names = new NameComponentManager(entities);
    _assetLoader = AssetLoader::create({_engine, _materialProvider, names, &entities});
    _resourceLoader = new ResourceLoader({.engine = _engine, .normalizeSkinningWeights = true});
    _stbDecoder = createStbProvider(_engine);
    _ktxDecoder = createKtx2Provider(_engine);
    _webpDecoder = isWebpSupported() ? createWebpProvider(_engine) : nullptr;
    _resourceLoader->addTextureProvider("image/png", _stbDecoder);
    _resourceLoader->addTextureProvider("image/jpeg", _stbDecoder);
    _resourceLoader->addTextureProvider("image/ktx2", _ktxDecoder);
    if (_webpDecoder) {
        _resourceLoader->addTextureProvider("image/webp", _webpDecoder);
    }

    _pan = 0.0f;
    _tilt = 0.0f;
    _zoom = 1.0f;
    _panSpeed = 0.0f;
    _tiltSpeed = 0.0f;
    _zoomSpeed = 0.0f;
    _animationDuration = 0.0;
    _flashUntil = 0.0;
    _lastFrameTime = CACurrentMediaTime();
    _selfTestStart = _lastFrameTime;
    _selfTestActive = YES;
    _asset = nullptr;

    [self loadModel];
    _displayLink = [CADisplayLink displayLinkWithTarget:self selector:@selector(renderFrame)];
    _displayLink.preferredFramesPerSecond = 60;
    [_displayLink addToRunLoop:NSRunLoop.mainRunLoop forMode:NSRunLoopCommonModes];
}

- (void)loadModel {
    NSString* path = [NSBundle.mainBundle pathForResource:@"security_camera" ofType:@"glb"];
    if (!path) {
        NSLog(@"[Filament] security_camera.glb missing from iOS app bundle");
        return;
    }
    NSData* data = [NSData dataWithContentsOfFile:path];
    if (!data) {
        NSLog(@"[Filament] failed to read %@", path);
        return;
    }
    _asset = _assetLoader->createAsset(
            static_cast<const uint8_t*>(data.bytes), static_cast<uint32_t>(data.length));
    if (!_asset) {
        NSLog(@"[Filament] glTF asset creation failed");
        return;
    }
    _scene->addEntities(_asset->getEntities(), _asset->getEntityCount());
    _resourceLoader->loadResources(_asset);
    [self transformToUnitCube];
    // transformToUnitCube centers the model at the origin. ModelViewer on
    // Android also moves its camera outside the model; without this view
    // matrix Filament leaves the camera at the origin, inside the mesh.
    _camera->lookAt({0.0f, 0.0f, 4.0f}, {0.0f, 0.0f, 0.0f}, {0.0f, 1.0f, 0.0f});

    TransformManager& transforms = _engine->getTransformManager();
    _yawPivot = Entity{};
    _pitchPivot = Entity{};
    for (size_t i = 0; i < _asset->getEntityCount(); ++i) {
        Entity entity = _asset->getEntities()[i];
        NSString* name = [self entityName:entity];
        if ([name isEqualToString:@"PTZ_Yaw_Pivot"]) {
            _yawPivot = entity;
            _yawInitial = transforms.getTransform(transforms.getInstance(entity));
        } else if ([name isEqualToString:@"PTZ_Pitch_Pivot"]) {
            _pitchPivot = entity;
            _pitchInitial = transforms.getTransform(transforms.getInstance(entity));
        } else if ([name hasPrefix:@"Plane"] || [name isEqualToString:@"PTZ_Background_Plane"]) {
            RenderableManager& renderables = _engine->getRenderableManager();
            auto instance = renderables.getInstance(entity);
            if (instance) {
                renderables.setLayerMask(instance, 0xFF, 0x00);
            }
            _scene->remove(entity);
        }
    }
    _asset->releaseSourceData();

    float3 harmonics[9] = {};
    harmonics[0] = {0.74f, 0.82f, 0.92f};
    _indirectLight = IndirectLight::Builder()
            .irradiance(1, harmonics)
            .intensity(45000.0f)
            .build(*_engine);
    _scene->setIndirectLight(_indirectLight);

    _lightEntity = EntityManager::get().create();
    LightManager::Builder(LightManager::Type::DIRECTIONAL)
            .color({0.96f, 0.98f, 1.0f})
            .intensity(75000.0f)
            .direction({0.3f, -0.8f, -0.5f})
            .castShadows(false)
            .build(*_engine, _lightEntity);
    _scene->addEntity(_lightEntity);
}

- (NSString*)entityName:(Entity)entity {
    NameComponentManager* names = _assetLoader->getNames();
    auto instance = names->getInstance(entity);
    if (!instance) {
        return @"";
    }
    return [NSString stringWithUTF8String:names->getName(instance)];
}

- (void)transformToUnitCube {
    if (!_asset) {
        return;
    }
    TransformManager& transforms = _engine->getTransformManager();
    auto bounds = _asset->getBoundingBox();
    float3 center = bounds.center();
    float maxExtent = max(bounds.extent()) * 2.0f;
    float scale = maxExtent > 0.0001f ? 2.0f / maxExtent : 1.0f;
    transforms.setTransform(
            transforms.getInstance(_asset->getRoot()),
            mat4f::scaling(scale) * mat4f::translation(-center));
}

- (void)layoutSubviews {
    [super layoutSubviews];
    if (!_filamentView || !_camera) {
        return;
    }
    uint32_t width = (uint32_t)std::max(1.0, self.bounds.size.width * self.contentScaleFactor);
    uint32_t height = (uint32_t)std::max(1.0, self.bounds.size.height * self.contentScaleFactor);
    _filamentView->setViewport({0, 0, width, height});
    ((CAMetalLayer*)self.layer).drawableSize = CGSizeMake(width, height);
    [self updateProjection];
}

- (void)updateProjection {
    if (!_camera || self.bounds.size.width <= 0.0 || self.bounds.size.height <= 0.0) {
        return;
    }
    float aspect = (float)self.bounds.size.width / (float)self.bounds.size.height;
    _camera->setProjection(kDefaultFov / std::max(_zoom, 1.0f), aspect, kNear, kFar);
}

- (void)setPanAngle:(float)pan tiltAngle:(float)tilt zoomLevel:(float)zoom duration:(double)duration {
    [self animateFromCurrentToPan:pan tilt:tilt zoom:zoom duration:duration];
}

- (void)setPanSpeed:(float)panSpeed tiltSpeed:(float)tiltSpeed zoomSpeed:(float)zoomSpeed {
    _panSpeed = panSpeed;
    _tiltSpeed = tiltSpeed;
    _zoomSpeed = zoomSpeed;
}

- (float)currentPanAngle {
    return _pan;
}

- (float)currentTiltAngle {
    return _tilt;
}

- (float)currentZoomLevel {
    return _zoom;
}

- (void)easeToPanAngle:(float)pan tiltAngle:(float)tilt zoomLevel:(float)zoom duration:(double)duration {
    [self animateFromCurrentToPan:pan tilt:tilt zoom:zoom duration:duration];
}

- (void)animateFromCurrentToPan:(float)pan tilt:(float)tilt zoom:(float)zoom duration:(double)duration {
    _fromPan = _pan;
    _fromTilt = _tilt;
    _fromZoom = _zoom;
    _toPan = pan;
    _toTilt = tilt;
    _toZoom = std::max(1.0f, zoom);
    _animationStart = CACurrentMediaTime();
    _animationDuration = std::max(0.0, duration);
    if (_animationDuration == 0.0) {
        _pan = _toPan;
        _tilt = _toTilt;
        _zoom = _toZoom;
        [self applyPose];
    }
}

- (void)applyPose {
    TransformManager& transforms = _engine->getTransformManager();
    if (transforms.hasComponent(_yawPivot)) {
        mat4f rotation = mat4f::rotation(_pan * kDegreesToRadians, float3{0, 0, 1});
        transforms.setTransform(transforms.getInstance(_yawPivot), _yawInitial * rotation);
    }
    if (transforms.hasComponent(_pitchPivot)) {
        // Filament's right-handed rotation has the opposite sign from the
        // Android ModelViewer matrix used by the PTZ protocol.
        mat4f rotation = mat4f::rotation(_tilt * kDegreesToRadians, float3{-1, 0, 0});
        transforms.setTransform(transforms.getInstance(_pitchPivot), _pitchInitial * rotation);
    }
    [self updateProjection];
}

- (void)renderFrame {
    if (!_renderer || !_swapChain || !_filamentView) {
        return;
    }
    const double now = CACurrentMediaTime();
    const double delta = std::min(now - _lastFrameTime, 0.1);
    _lastFrameTime = now;

    if (_selfTestActive) {
        const double t = now - _selfTestStart;
        float pan = 0.0f;
        float tilt = 0.0f;
        if (t < 0.5) {
            // Initial settle.
        } else if (t < 1.5) {
            pan = [self easedValueFrom:0.0f to:-50.0f phase:(t - 0.5) / 1.0];
        } else if (t < 2.5) {
            pan = [self easedValueFrom:-50.0f to:50.0f phase:(t - 1.5) / 1.0];
        } else if (t < 3.0) {
            pan = [self easedValueFrom:50.0f to:0.0f phase:(t - 2.5) / 0.5];
        } else if (t < 3.5) {
            // Horizontal settle.
        } else if (t < 4.5) {
            tilt = [self easedValueFrom:0.0f to:25.0f phase:(t - 3.5) / 1.0];
        } else if (t < 5.5) {
            tilt = [self easedValueFrom:25.0f to:-25.0f phase:(t - 4.5) / 1.0];
        } else if (t < 6.0) {
            tilt = [self easedValueFrom:-25.0f to:0.0f phase:(t - 5.5) / 0.5];
        } else {
            _selfTestActive = NO;
        }
        if (_selfTestActive) {
            _pan = pan;
            _tilt = tilt;
            _zoom = 1.0f;
            [self applyPose];
        }
    }

    if (!_selfTestActive && _animationDuration > 0.0) {
        double progress = std::clamp((now - _animationStart) / _animationDuration, 0.0, 1.0);
        float eased = (float)(0.5 - 0.5 * cos(progress * M_PI));
        _pan = _fromPan + (_toPan - _fromPan) * eased;
        _tilt = _fromTilt + (_toTilt - _fromTilt) * eased;
        _zoom = _fromZoom + (_toZoom - _fromZoom) * eased;
        if (progress >= 1.0) {
            _animationDuration = 0.0;
        }
        [self applyPose];
    } else if (!_selfTestActive && _animationDuration <= 0.0) {
        _pan = std::clamp(_pan + _panSpeed * (float)delta, -180.0f, 180.0f);
        _tilt = std::clamp(_tilt + _tiltSpeed * (float)delta, -90.0f, 90.0f);
        _zoom = std::clamp(_zoom + _zoomSpeed * (float)delta, 1.0f, 16.0f);
        if (_panSpeed != 0.0f || _tiltSpeed != 0.0f || _zoomSpeed != 0.0f) {
            [self applyPose];
        }
    }

    Renderer::ClearOptions clear = {.clear = true, .clearColor = kBackgroundColor};
    if (CACurrentMediaTime() < _flashUntil) {
        clear.clearColor = {1.0, 1.0, 1.0, 1.0};
    }
    _renderer->setClearOptions(clear);
    if (_renderer->beginFrame(_swapChain)) {
        _renderer->render(_filamentView);
        _renderer->endFrame();
    }
}

- (void)triggerIFrameFlash {
    _flashUntil = CACurrentMediaTime() + 0.22;
}

- (void)triggerSnapshotFlash {
    _flashUntil = CACurrentMediaTime() + 0.16;
}

- (void)triggerRebootAnimation {
    _selfTestActive = YES;
    _selfTestStart = CACurrentMediaTime();
    _animationDuration = 0.0;
}

- (float)easedValueFrom:(float)from to:(float)to phase:(double)phase {
    const double p = std::clamp(phase, 0.0, 1.0);
    const float eased = (float)(0.5 - 0.5 * cos(p * M_PI));
    return from + (to - from) * eased;
}

- (void)stopRendering {
    [_displayLink invalidate];
    _displayLink = nil;
}

- (void)dealloc {
    [self stopRendering];
    if (_asset) {
        _resourceLoader->evictResourceData();
        _scene->removeEntities(_asset->getEntities(), _asset->getEntityCount());
        _assetLoader->destroyAsset(_asset);
    }
    if (_lightEntity) {
        _scene->remove(_lightEntity);
        EntityManager::get().destroy(_lightEntity);
    }
    _materialProvider->destroyMaterials();
    delete _materialProvider;
    delete _resourceLoader;
    delete _stbDecoder;
    delete _ktxDecoder;
    delete _webpDecoder;
    auto* names = _assetLoader->getNames();
    delete names;
    AssetLoader::destroy(&_assetLoader);
    _engine->destroy(_swapChain);
    _engine->destroy(_filamentView);
    _engine->destroyCameraComponent(_cameraEntity);
    EntityManager::get().destroy(_cameraEntity);
    _engine->destroy(_scene);
    _engine->destroy(_renderer);
    _engine->destroy(&_engine);
}

@end
