import { describe, expect, it } from 'vitest';
import { Matrix4, PerspectiveCamera, Ray, Vector3 } from 'three';
import { updateCameraUniforms } from './updateCameraUniforms';

describe('terrain camera synchronization', () => {
  it('keeps terrain rays aligned with projected markers on every moving frame', () => {
    const camera = new PerspectiveCamera(50, 1.5, 0.5, 4000);
    camera.position.set(0, 120, 180);
    camera.lookAt(0, 0, 0);
    camera.updateMatrixWorld();
    const uniforms = {
      uCameraPosition: { value: new Vector3() },
      uProjectionMatrix: { value: new Matrix4() },
      uViewMatrix: { value: new Matrix4() },
      uInvProjectionMatrix: { value: new Matrix4() },
      uInvViewMatrix: { value: new Matrix4() },
    };
    const marker = new Vector3(22, 8, -10);
    for (const position of [[40, 130, 160], [-30, 100, 150], [10, 180, 220]]) {
      // Controls mutate transforms before render updates the camera matrices.
      camera.position.set(position[0], position[1], position[2]);
      camera.lookAt(8, 3, 0);
      updateCameraUniforms(uniforms, camera);
      const screen = marker.clone().project(camera);
      const rayPoint = new Vector3(screen.x, screen.y, 0)
        .applyMatrix4(uniforms.uInvProjectionMatrix.value)
        .applyMatrix4(uniforms.uInvViewMatrix.value);
      const origin = uniforms.uCameraPosition.value;
      const terrainRay = new Ray(origin, rayPoint.sub(origin).normalize());
      expect(terrainRay.distanceToPoint(marker)).toBeLessThan(0.00001);
      const shaderClip = marker.clone().applyMatrix4(uniforms.uViewMatrix.value)
        .applyMatrix4(uniforms.uProjectionMatrix.value);
      expect(shaderClip.distanceTo(screen)).toBeLessThan(0.00001);
    }
  });
});
