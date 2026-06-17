import math
import os

import bpy
from mathutils import Vector


SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT_DIR = os.path.abspath(os.path.join(SCRIPT_DIR, '..', '..'))
ASSET_DIR = os.path.join(ROOT_DIR, 'composeApp', 'src', 'androidMain', 'assets')
REVIEW_DIR = os.path.join(ROOT_DIR, 'preview', 'ptz-camera', 'review')

OUTPUT_GLB = os.path.join(ASSET_DIR, 'generated_ptz_camera_v2.glb')
OUTPUT_BLEND = os.path.join(REVIEW_DIR, 'generated_ptz_camera_v2.blend')
OUTPUT_RENDER = os.path.join(REVIEW_DIR, 'generated_ptz_camera_v2.png')


def clear_scene():
    bpy.ops.object.select_all(action='SELECT')
    bpy.ops.object.delete(use_global=False)

    for datablocks in (
        bpy.data.meshes,
        bpy.data.materials,
        bpy.data.images,
        bpy.data.curves,
        bpy.data.cameras,
        bpy.data.lights,
    ):
        for datablock in list(datablocks):
            if datablock.users == 0:
                datablocks.remove(datablock)


def ensure_dirs():
    os.makedirs(ASSET_DIR, exist_ok=True)
    os.makedirs(REVIEW_DIR, exist_ok=True)


def set_active(obj):
    bpy.context.view_layer.objects.active = obj
    obj.select_set(True)


def smooth_object(obj):
    if obj.type != 'MESH':
        return
    for poly in obj.data.polygons:
        poly.use_smooth = True
    if hasattr(obj.data, 'use_auto_smooth'):
        obj.data.use_auto_smooth = True
        obj.data.auto_smooth_angle = math.radians(52)


def apply_material(obj, material):
    if obj.type != 'MESH':
        return
    obj.data.materials.clear()
    obj.data.materials.append(material)


def add_bevel(obj, width, segments=4):
    mod = obj.modifiers.new(name='Bevel', type='BEVEL')
    mod.width = width
    mod.segments = segments
    mod.limit_method = 'ANGLE'
    mod.harden_normals = True


def add_weighted_normals(obj):
    mod = obj.modifiers.new(name='WeightedNormals', type='WEIGHTED_NORMAL')
    mod.keep_sharp = True


def new_material(name, base_color, roughness, metallic=0.0, specular=0.5, emission=None):
    mat = bpy.data.materials.new(name=name)
    mat.use_nodes = True
    nodes = mat.node_tree.nodes
    bsdf = nodes['Principled BSDF']
    bsdf.inputs['Base Color'].default_value = (*base_color, 1.0)
    bsdf.inputs['Roughness'].default_value = roughness
    bsdf.inputs['Metallic'].default_value = metallic
    if 'Specular IOR Level' in bsdf.inputs:
        bsdf.inputs['Specular IOR Level'].default_value = specular
    elif 'Specular' in bsdf.inputs:
        bsdf.inputs['Specular'].default_value = specular
    if emission is not None:
        bsdf.inputs['Emission Color'].default_value = (*emission, 1.0)
        bsdf.inputs['Emission Strength'].default_value = 0.4
    return mat


def add_empty(name, location=(0, 0, 0), parent=None):
    empty = bpy.data.objects.new(name, None)
    empty.empty_display_type = 'PLAIN_AXES'
    empty.location = location
    bpy.context.scene.collection.objects.link(empty)
    if parent is not None:
        empty.parent = parent
    return empty


def add_cube(name, location, scale, material, parent=None, bevel_ratio=0.18):
    bpy.ops.mesh.primitive_cube_add(location=location)
    obj = bpy.context.active_object
    obj.name = name
    obj.scale = scale
    if parent is not None:
        obj.parent = parent
    apply_material(obj, material)
    smooth_object(obj)
    bevel_width = max(0.003, min(scale) * bevel_ratio)
    add_bevel(obj, bevel_width)
    add_weighted_normals(obj)
    return obj


def add_cylinder(name, location, radius, depth, material, parent=None, rotation=(0, 0, 0), vertices=48, bevel_ratio=0.08):
    bpy.ops.mesh.primitive_cylinder_add(
        vertices=vertices,
        radius=radius,
        depth=depth,
        location=location,
        rotation=rotation,
    )
    obj = bpy.context.active_object
    obj.name = name
    if parent is not None:
        obj.parent = parent
    apply_material(obj, material)
    smooth_object(obj)
    add_bevel(obj, max(0.002, radius * bevel_ratio), segments=3)
    add_weighted_normals(obj)
    return obj


def add_torus(name, location, major_radius, minor_radius, material, parent=None, rotation=(0, 0, 0), segments=40, ring_segments=18):
    bpy.ops.mesh.primitive_torus_add(
        major_segments=segments,
        minor_segments=ring_segments,
        major_radius=major_radius,
        minor_radius=minor_radius,
        location=location,
        rotation=rotation,
    )
    obj = bpy.context.active_object
    obj.name = name
    if parent is not None:
        obj.parent = parent
    apply_material(obj, material)
    smooth_object(obj)
    return obj


def look_at(obj, target):
    direction = Vector(target) - obj.location
    quat = direction.to_track_quat('-Z', 'Y')
    obj.rotation_euler = quat.to_euler()


def build_camera_model():
    body = new_material('BodyGraphite', (0.16, 0.19, 0.22), roughness=0.42, metallic=0.55, specular=0.55)
    trim = new_material('TrimCharcoal', (0.08, 0.09, 0.11), roughness=0.55, metallic=0.35, specular=0.45)
    ceramic = new_material('CeramicWhite', (0.83, 0.85, 0.88), roughness=0.52, metallic=0.05, specular=0.5)
    lens = new_material('LensGlass', (0.04, 0.07, 0.10), roughness=0.05, metallic=0.0, specular=0.8, emission=(0.02, 0.05, 0.08))
    sensor = new_material('SensorBlack', (0.03, 0.03, 0.04), roughness=0.18, metallic=0.02, specular=0.7)

    root = add_empty('PTZ_Root', (0, 0, 0))

    add_cube('PTZ_Base_Plate', (0, 0, 0.045), (0.42, 0.32, 0.045), trim, parent=root, bevel_ratio=0.2)
    add_cylinder('PTZ_Base_Pedestal', (0, 0, 0.275), 0.075, 0.42, trim, parent=root)
    add_cylinder('PTZ_Base_Collar', (0, 0, 0.49), 0.13, 0.07, body, parent=root)

    yaw = add_empty('PTZ_Yaw_Pivot', (0, 0, 0.58), parent=root)
    add_cylinder('PTZ_Yaw_Hub', (0, 0, 0.0), 0.16, 0.08, body, parent=yaw)
    add_cube('PTZ_Yoke_Back', (0, -0.07, -0.15), (0.18, 0.055, 0.05), trim, parent=yaw)
    add_cube('PTZ_Yoke_Left', (-0.23, 0.00, -0.065), (0.045, 0.07, 0.22), ceramic, parent=yaw, bevel_ratio=0.25)
    add_cube('PTZ_Yoke_Right', (0.23, 0.00, -0.065), (0.045, 0.07, 0.22), ceramic, parent=yaw, bevel_ratio=0.25)
    add_cylinder('PTZ_Yoke_Left_Cap', (-0.23, 0.02, 0.165), 0.028, 0.035, trim, parent=yaw, rotation=(0, math.radians(90), 0))
    add_cylinder('PTZ_Yoke_Right_Cap', (0.23, 0.02, 0.165), 0.028, 0.035, trim, parent=yaw, rotation=(0, math.radians(90), 0))

    pitch = add_empty('PTZ_Pitch_Pivot', (0, 0.02, 0.16), parent=yaw)
    add_cylinder('PTZ_Axle_Left', (-0.205, 0.0, 0.0), 0.032, 0.035, trim, parent=pitch, rotation=(0, math.radians(90), 0))
    add_cylinder('PTZ_Axle_Right', (0.205, 0.0, 0.0), 0.032, 0.035, trim, parent=pitch, rotation=(0, math.radians(90), 0))

    add_cylinder('PTZ_Body_Barrel', (0, 0.19, 0.0), 0.17, 0.46, body, parent=pitch, rotation=(math.radians(90), 0, 0), vertices=56)
    add_cylinder('PTZ_Body_Rear', (0, -0.03, -0.005), 0.135, 0.12, trim, parent=pitch, rotation=(math.radians(90), 0, 0), vertices=48)
    add_cube('PTZ_Body_Saddle', (0, 0.14, -0.12), (0.11, 0.16, 0.045), trim, parent=pitch, bevel_ratio=0.22)
    add_cube('PTZ_Body_Hood', (0, 0.22, 0.115), (0.205, 0.28, 0.028), ceramic, parent=pitch, bevel_ratio=0.3)
    add_cube('PTZ_Hood_Left_Fin', (-0.175, 0.30, 0.06), (0.015, 0.155, 0.055), ceramic, parent=pitch, bevel_ratio=0.28)
    add_cube('PTZ_Hood_Right_Fin', (0.175, 0.30, 0.06), (0.015, 0.155, 0.055), ceramic, parent=pitch, bevel_ratio=0.28)
    add_cube('PTZ_Rear_Service_Hatch', (0, -0.065, 0.02), (0.09, 0.04, 0.08), body, parent=pitch, bevel_ratio=0.25)

    zoom = add_empty('PTZ_Zoom_Sled', (0, 0.405, -0.005), parent=pitch)
    add_cylinder('PTZ_Front_Bezel', (0, 0.0, 0.0), 0.132, 0.08, ceramic, parent=zoom, rotation=(math.radians(90), 0, 0), vertices=56)
    add_cylinder('PTZ_Lens_Barrel', (0, 0.07, 0.0), 0.09, 0.18, trim, parent=zoom, rotation=(math.radians(90), 0, 0), vertices=56)
    add_cylinder('PTZ_Lens_Inner', (0, 0.10, 0.0), 0.058, 0.12, sensor, parent=zoom, rotation=(math.radians(90), 0, 0), vertices=48)
    add_cylinder('PTZ_Lens_Glass', (0, 0.155, 0.0), 0.052, 0.014, lens, parent=zoom, rotation=(math.radians(90), 0, 0), vertices=48)
    add_torus('PTZ_IR_Ring', (0, 0.138, 0.0), 0.088, 0.011, trim, parent=zoom, rotation=(math.radians(90), 0, 0))
    add_cube('PTZ_IR_Module', (0, -0.01, -0.10), (0.07, 0.03, 0.035), sensor, parent=zoom, bevel_ratio=0.2)

    return root


def export_glb():
    bpy.ops.export_scene.gltf(
        filepath=OUTPUT_GLB,
        export_format='GLB',
        use_visible=True,
        export_apply=False,
        export_yup=True,
        export_materials='EXPORT',
        export_cameras=False,
        export_lights=False,
        export_animations=False,
    )


def setup_render_scene():
    scene = bpy.context.scene
    if 'BLENDER_EEVEE_NEXT' in bpy.context.preferences.addons['cycles'].preferences.compute_device_type if False else []:
        pass
    scene.render.engine = 'BLENDER_EEVEE_NEXT' if 'BLENDER_EEVEE_NEXT' in bpy.types.RenderSettings.bl_rna.properties['engine'].enum_items.keys() else 'BLENDER_EEVEE'
    scene.render.resolution_x = 1600
    scene.render.resolution_y = 1200
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = 'PNG'
    scene.render.filepath = OUTPUT_RENDER
    scene.eevee.taa_render_samples = 64

    world = scene.world
    if world is None:
        world = bpy.data.worlds.new('World')
        scene.world = world
    world.use_nodes = True
    bg = world.node_tree.nodes['Background']
    bg.inputs[0].default_value = (0.015, 0.018, 0.024, 1.0)
    bg.inputs[1].default_value = 0.7

    floor_mat = new_material('Floor', (0.17, 0.19, 0.22), roughness=0.92, metallic=0.0)
    bpy.ops.mesh.primitive_plane_add(location=(0, 0, 0), size=8)
    floor = bpy.context.active_object
    floor.name = 'RenderFloor'
    floor.location.z = -0.002
    apply_material(floor, floor_mat)

    bpy.ops.object.light_add(type='AREA', location=(2.8, -2.9, 2.4))
    key = bpy.context.active_object
    key.data.energy = 3600
    key.data.shape = 'RECTANGLE'
    key.data.size = 2.4
    key.data.size_y = 2.0
    look_at(key, (0, 0.15, 0.48))

    bpy.ops.object.light_add(type='AREA', location=(-2.1, 1.8, 1.3))
    fill = bpy.context.active_object
    fill.data.energy = 1200
    fill.data.shape = 'RECTANGLE'
    fill.data.size = 2.0
    fill.data.size_y = 1.5
    look_at(fill, (0, 0.18, 0.45))

    bpy.ops.object.light_add(type='SUN', location=(0, 0, 3.5))
    sun = bpy.context.active_object
    sun.data.energy = 1.8
    sun.rotation_euler = (math.radians(42), math.radians(0), math.radians(28))

    bpy.ops.object.camera_add(location=(2.45, -2.75, 1.7))
    camera = bpy.context.active_object
    camera.data.lens = 58
    look_at(camera, (0, 0.10, 0.52))
    scene.camera = camera


def save_blend():
    bpy.ops.wm.save_as_mainfile(filepath=OUTPUT_BLEND)


def render_preview():
    bpy.ops.render.render(write_still=True)


def main():
    ensure_dirs()
    clear_scene()
    build_camera_model()
    export_glb()
    setup_render_scene()
    save_blend()
    render_preview()
    print(f'Wrote {OUTPUT_GLB}')
    print(f'Wrote {OUTPUT_BLEND}')
    print(f'Wrote {OUTPUT_RENDER}')


main()
