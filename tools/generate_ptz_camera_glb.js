#!/usr/bin/env node

const fs = require('fs');
const path = require('path');

const OUTPUT_PATH = path.resolve(
  __dirname,
  '../composeApp/src/androidMain/assets/generated_ptz_camera.glb'
);

function align4(value) {
  return (4 - (value % 4)) % 4;
}

function typedBuffer(typedArray) {
  return Buffer.from(
    typedArray.buffer,
    typedArray.byteOffset,
    typedArray.byteLength
  );
}

function computeMinMax(values, itemSize) {
  const min = new Array(itemSize).fill(Number.POSITIVE_INFINITY);
  const max = new Array(itemSize).fill(Number.NEGATIVE_INFINITY);
  for (let i = 0; i < values.length; i += itemSize) {
    for (let j = 0; j < itemSize; j += 1) {
      const value = values[i + j];
      if (value < min[j]) min[j] = value;
      if (value > max[j]) max[j] = value;
    }
  }
  return { min, max };
}

function makeBoxGeometry() {
  const positions = [];
  const normals = [];
  const indices = [];
  const faces = [
    {
      normal: [0, 0, 1],
      corners: [
        [-0.5, -0.5, 0.5],
        [0.5, -0.5, 0.5],
        [0.5, 0.5, 0.5],
        [-0.5, 0.5, 0.5],
      ],
    },
    {
      normal: [0, 0, -1],
      corners: [
        [0.5, -0.5, -0.5],
        [-0.5, -0.5, -0.5],
        [-0.5, 0.5, -0.5],
        [0.5, 0.5, -0.5],
      ],
    },
    {
      normal: [1, 0, 0],
      corners: [
        [0.5, -0.5, 0.5],
        [0.5, -0.5, -0.5],
        [0.5, 0.5, -0.5],
        [0.5, 0.5, 0.5],
      ],
    },
    {
      normal: [-1, 0, 0],
      corners: [
        [-0.5, -0.5, -0.5],
        [-0.5, -0.5, 0.5],
        [-0.5, 0.5, 0.5],
        [-0.5, 0.5, -0.5],
      ],
    },
    {
      normal: [0, 1, 0],
      corners: [
        [-0.5, 0.5, 0.5],
        [0.5, 0.5, 0.5],
        [0.5, 0.5, -0.5],
        [-0.5, 0.5, -0.5],
      ],
    },
    {
      normal: [0, -1, 0],
      corners: [
        [-0.5, -0.5, -0.5],
        [0.5, -0.5, -0.5],
        [0.5, -0.5, 0.5],
        [-0.5, -0.5, 0.5],
      ],
    },
  ];

  for (const face of faces) {
    const start = positions.length / 3;
    for (const corner of face.corners) {
      positions.push(...corner);
      normals.push(...face.normal);
    }
    indices.push(start, start + 1, start + 2, start, start + 2, start + 3);
  }

  return {
    positions: new Float32Array(positions),
    normals: new Float32Array(normals),
    indices: new Uint16Array(indices),
  };
}

function makeCylinderGeometry(segments = 20) {
  const positions = [];
  const normals = [];
  const indices = [];

  for (let i = 0; i < segments; i += 1) {
    const a0 = (i / segments) * Math.PI * 2;
    const a1 = ((i + 1) / segments) * Math.PI * 2;
    const c0 = Math.cos(a0);
    const s0 = Math.sin(a0);
    const c1 = Math.cos(a1);
    const s1 = Math.sin(a1);
    const start = positions.length / 3;

    positions.push(
      c0 * 0.5, s0 * 0.5, -0.5,
      c0 * 0.5, s0 * 0.5, 0.5,
      c1 * 0.5, s1 * 0.5, 0.5,
      c1 * 0.5, s1 * 0.5, -0.5
    );
    normals.push(
      c0, s0, 0,
      c0, s0, 0,
      c1, s1, 0,
      c1, s1, 0
    );
    indices.push(start, start + 1, start + 2, start, start + 2, start + 3);
  }

  const frontCenter = positions.length / 3;
  positions.push(0, 0, 0.5);
  normals.push(0, 0, 1);
  for (let i = 0; i < segments; i += 1) {
    const a0 = (i / segments) * Math.PI * 2;
    const a1 = ((i + 1) / segments) * Math.PI * 2;
    const c0 = Math.cos(a0);
    const s0 = Math.sin(a0);
    const c1 = Math.cos(a1);
    const s1 = Math.sin(a1);
    const start = positions.length / 3;
    positions.push(c0 * 0.5, s0 * 0.5, 0.5, c1 * 0.5, s1 * 0.5, 0.5);
    normals.push(0, 0, 1, 0, 0, 1);
    indices.push(frontCenter, start, start + 1);
  }

  const backCenter = positions.length / 3;
  positions.push(0, 0, -0.5);
  normals.push(0, 0, -1);
  for (let i = 0; i < segments; i += 1) {
    const a0 = (i / segments) * Math.PI * 2;
    const a1 = ((i + 1) / segments) * Math.PI * 2;
    const c0 = Math.cos(a0);
    const s0 = Math.sin(a0);
    const c1 = Math.cos(a1);
    const s1 = Math.sin(a1);
    const start = positions.length / 3;
    positions.push(c1 * 0.5, s1 * 0.5, -0.5, c0 * 0.5, s0 * 0.5, -0.5);
    normals.push(0, 0, -1, 0, 0, -1);
    indices.push(backCenter, start, start + 1);
  }

  return {
    positions: new Float32Array(positions),
    normals: new Float32Array(normals),
    indices: new Uint16Array(indices),
  };
}

class GlbBuilder {
  constructor() {
    this.bufferParts = [];
    this.bufferOffset = 0;
    this.bufferViews = [];
    this.accessors = [];
    this.materials = [];
    this.meshes = [];
    this.nodes = [];
  }

  addBufferView(typedArray, target) {
    const pad = align4(this.bufferOffset);
    if (pad > 0) {
      this.bufferParts.push(Buffer.alloc(pad));
      this.bufferOffset += pad;
    }

    const viewIndex = this.bufferViews.length;
    const buffer = typedBuffer(typedArray);
    this.bufferParts.push(buffer);
    this.bufferViews.push({
      buffer: 0,
      byteOffset: this.bufferOffset,
      byteLength: buffer.length,
      target,
    });
    this.bufferOffset += buffer.length;
    return viewIndex;
  }

  addAccessor(options) {
    const accessorIndex = this.accessors.length;
    this.accessors.push(options);
    return accessorIndex;
  }

  addGeometry(name, geometry) {
    const positionView = this.addBufferView(geometry.positions, 34962);
    const normalView = this.addBufferView(geometry.normals, 34962);
    const indexView = this.addBufferView(geometry.indices, 34963);
    const bounds = computeMinMax(geometry.positions, 3);

    const positionAccessor = this.addAccessor({
      name: `${name}_POSITION`,
      bufferView: positionView,
      componentType: 5126,
      count: geometry.positions.length / 3,
      type: 'VEC3',
      min: bounds.min,
      max: bounds.max,
    });

    const normalAccessor = this.addAccessor({
      name: `${name}_NORMAL`,
      bufferView: normalView,
      componentType: 5126,
      count: geometry.normals.length / 3,
      type: 'VEC3',
    });

    const indexAccessor = this.addAccessor({
      name: `${name}_INDICES`,
      bufferView: indexView,
      componentType: 5123,
      count: geometry.indices.length,
      type: 'SCALAR',
    });

    return { positionAccessor, normalAccessor, indexAccessor };
  }

  addMaterial(name, baseColorFactor, metallicFactor, roughnessFactor) {
    const materialIndex = this.materials.length;
    this.materials.push({
      name,
      pbrMetallicRoughness: {
        baseColorFactor,
        metallicFactor,
        roughnessFactor,
      },
    });
    return materialIndex;
  }

  addMesh(name, geometryRefs, materialIndex) {
    const meshIndex = this.meshes.length;
    this.meshes.push({
      name,
      primitives: [
        {
          attributes: {
            POSITION: geometryRefs.positionAccessor,
            NORMAL: geometryRefs.normalAccessor,
          },
          indices: geometryRefs.indexAccessor,
          material: materialIndex,
          mode: 4,
        },
      ],
    });
    return meshIndex;
  }

  addNode(node) {
    const nodeIndex = this.nodes.length;
    this.nodes.push(node);
    return nodeIndex;
  }

  toGlb(sceneRoots) {
    const binPadding = align4(this.bufferOffset);
    if (binPadding > 0) {
      this.bufferParts.push(Buffer.alloc(binPadding));
      this.bufferOffset += binPadding;
    }

    const json = {
      asset: {
        version: '2.0',
        generator: 'Codex PTZ camera generator',
      },
      scene: 0,
      scenes: [
        {
          name: 'PTZCameraScene',
          nodes: sceneRoots,
        },
      ],
      nodes: this.nodes,
      meshes: this.meshes,
      materials: this.materials,
      accessors: this.accessors,
      bufferViews: this.bufferViews,
      buffers: [
        {
          byteLength: this.bufferOffset,
        },
      ],
    };

    const jsonBuffer = Buffer.from(JSON.stringify(json), 'utf8');
    const jsonPadding = align4(jsonBuffer.length);
    const jsonChunk = Buffer.concat([jsonBuffer, Buffer.alloc(jsonPadding, 0x20)]);
    const binChunk = Buffer.concat(this.bufferParts);

    const totalLength =
      12 +
      8 + jsonChunk.length +
      8 + binChunk.length;

    const header = Buffer.alloc(12);
    header.writeUInt32LE(0x46546c67, 0);
    header.writeUInt32LE(2, 4);
    header.writeUInt32LE(totalLength, 8);

    const jsonHeader = Buffer.alloc(8);
    jsonHeader.writeUInt32LE(jsonChunk.length, 0);
    jsonHeader.writeUInt32LE(0x4e4f534a, 4);

    const binHeader = Buffer.alloc(8);
    binHeader.writeUInt32LE(binChunk.length, 0);
    binHeader.writeUInt32LE(0x004e4942, 4);

    return Buffer.concat([header, jsonHeader, jsonChunk, binHeader, binChunk]);
  }
}

function generatePtzCamera() {
  const builder = new GlbBuilder();

  const box = builder.addGeometry('UnitBox', makeBoxGeometry());
  const cylinder = builder.addGeometry('UnitCylinder', makeCylinderGeometry(20));

  const matDark = builder.addMaterial('Metal_Dark', [0.12, 0.15, 0.18, 1], 0.85, 0.44);
  const matGraphite = builder.addMaterial('Graphite', [0.24, 0.27, 0.31, 1], 0.22, 0.76);
  const matCeramic = builder.addMaterial('Ceramic_Light', [0.82, 0.85, 0.89, 1], 0.06, 0.58);
  const matLens = builder.addMaterial('Lens_Glass', [0.08, 0.13, 0.2, 1], 0.02, 0.08);

  const meshBoxDark = builder.addMesh('Box_Dark', box, matDark);
  const meshBoxGraphite = builder.addMesh('Box_Graphite', box, matGraphite);
  const meshBoxCeramic = builder.addMesh('Box_Ceramic', box, matCeramic);
  const meshCylinderDark = builder.addMesh('Cylinder_Dark', cylinder, matDark);
  const meshCylinderCeramic = builder.addMesh('Cylinder_Ceramic', cylinder, matCeramic);
  const meshCylinderLens = builder.addMesh('Cylinder_Lens', cylinder, matLens);

  const nodes = {};

  nodes.root = builder.addNode({
    name: 'PTZ_Root',
    children: [],
  });
  nodes.basePlate = builder.addNode({
    name: 'PTZ_Base_Plate',
    mesh: meshBoxGraphite,
    translation: [0, -1.05, 0],
    scale: [1.55, 0.12, 1.55],
  });
  nodes.mast = builder.addNode({
    name: 'PTZ_Mast',
    mesh: meshBoxGraphite,
    translation: [0, -0.31, 0],
    scale: [0.34, 0.92, 0.34],
  });
  nodes.yawPivot = builder.addNode({
    name: 'PTZ_Yaw_Pivot',
    translation: [0, 0.22, 0],
    children: [],
  });
  nodes.yokeLeft = builder.addNode({
    name: 'PTZ_Yoke_Left',
    mesh: meshBoxCeramic,
    translation: [-0.72, 0.0, 0],
    scale: [0.14, 0.64, 0.24],
  });
  nodes.yokeRight = builder.addNode({
    name: 'PTZ_Yoke_Right',
    mesh: meshBoxCeramic,
    translation: [0.72, 0.0, 0],
    scale: [0.14, 0.64, 0.24],
  });
  nodes.yokeBridge = builder.addNode({
    name: 'PTZ_Yoke_Bridge',
    mesh: meshBoxGraphite,
    translation: [0, 0.28, -0.18],
    scale: [1.62, 0.1, 0.26],
  });
  nodes.pitchPivot = builder.addNode({
    name: 'PTZ_Pitch_Pivot',
    translation: [0, 0, 0],
    children: [],
  });
  nodes.bodyShell = builder.addNode({
    name: 'PTZ_Body_Shell',
    mesh: meshBoxDark,
    translation: [0, 0, 0],
    scale: [1.08, 0.54, 0.86],
  });
  nodes.hood = builder.addNode({
    name: 'PTZ_Hood',
    mesh: meshBoxGraphite,
    translation: [0, 0.34, -0.04],
    scale: [1.16, 0.16, 0.96],
  });
  nodes.rearCap = builder.addNode({
    name: 'PTZ_Rear_Cap',
    mesh: meshBoxGraphite,
    translation: [0, -0.02, -0.5],
    scale: [0.82, 0.44, 0.14],
  });
  nodes.chin = builder.addNode({
    name: 'PTZ_Chin',
    mesh: meshBoxDark,
    translation: [0, -0.23, 0.22],
    scale: [0.56, 0.14, 0.24],
  });
  nodes.zoomSled = builder.addNode({
    name: 'PTZ_Zoom_Sled',
    translation: [0, -0.03, 0.58],
    children: [],
  });
  nodes.lensBarrel = builder.addNode({
    name: 'PTZ_Lens_Barrel',
    mesh: meshCylinderDark,
    translation: [0, 0, 0],
    scale: [0.38, 0.38, 0.92],
  });
  nodes.lensCollar = builder.addNode({
    name: 'PTZ_Lens_Collar',
    mesh: meshCylinderCeramic,
    translation: [0, 0, -0.22],
    scale: [0.5, 0.5, 0.14],
  });
  nodes.lensGlass = builder.addNode({
    name: 'PTZ_Lens_Glass',
    mesh: meshCylinderLens,
    translation: [0, 0, 0.47],
    scale: [0.28, 0.28, 0.06],
  });
  nodes.irModule = builder.addNode({
    name: 'PTZ_IR_Module',
    mesh: meshBoxCeramic,
    translation: [0, -0.24, 0.28],
    scale: [0.22, 0.08, 0.18],
  });

  builder.nodes[nodes.root].children.push(nodes.basePlate, nodes.mast, nodes.yawPivot);
  builder.nodes[nodes.yawPivot].children.push(
    nodes.yokeLeft,
    nodes.yokeRight,
    nodes.yokeBridge,
    nodes.pitchPivot
  );
  builder.nodes[nodes.pitchPivot].children.push(
    nodes.bodyShell,
    nodes.hood,
    nodes.rearCap,
    nodes.chin,
    nodes.zoomSled
  );
  builder.nodes[nodes.zoomSled].children.push(
    nodes.lensBarrel,
    nodes.lensCollar,
    nodes.lensGlass,
    nodes.irModule
  );

  return {
    glb: builder.toGlb([nodes.root]),
    nodeSummary: [
      'PTZ_Root',
      'PTZ_Base_Plate',
      'PTZ_Mast',
      'PTZ_Yaw_Pivot',
      'PTZ_Yoke_Left',
      'PTZ_Yoke_Right',
      'PTZ_Yoke_Bridge',
      'PTZ_Pitch_Pivot',
      'PTZ_Body_Shell',
      'PTZ_Hood',
      'PTZ_Rear_Cap',
      'PTZ_Chin',
      'PTZ_Zoom_Sled',
      'PTZ_Lens_Barrel',
      'PTZ_Lens_Collar',
      'PTZ_Lens_Glass',
      'PTZ_IR_Module',
    ],
  };
}

function main() {
  const { glb, nodeSummary } = generatePtzCamera();
  fs.mkdirSync(path.dirname(OUTPUT_PATH), { recursive: true });
  fs.writeFileSync(OUTPUT_PATH, glb);

  const rel = path.relative(process.cwd(), OUTPUT_PATH) || OUTPUT_PATH;
  console.log(`Wrote ${rel}`);
  console.log(`Size ${(glb.length / 1024).toFixed(1)} KiB`);
  console.log('Nodes');
  for (const name of nodeSummary) {
    console.log(`- ${name}`);
  }
}

main();
