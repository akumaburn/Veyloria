import 'react';

declare module 'react' {
  namespace JSX {
    interface IntrinsicElements {
      group: any;
      mesh: any;
      instancedMesh: any;
      shaderMaterial: any;
      meshStandardMaterial: any;
      meshBasicMaterial: any;
      sphereGeometry: any;
      boxGeometry: any;
      ringGeometry: any;
      planeGeometry: any;
      pointLight: any;
      ambientLight: any;
      directionalLight: any;
      spotLight: any;
      bufferGeometry: any;
      shapeGeometry: any;
      line: any;
      lineSegments: any;
      points: any;
      pointsMaterial: any;
      sprite: any;
      spriteMaterial: any;
      perspectiveCamera: any;
      orthographicCamera: any;
      scene: any;
      gridHelper: any;
      arrowHelper: any;
      axesHelper: any;
    }
  }
}
