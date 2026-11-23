import type { Camera, IUniform } from 'three';

/** Flush camera movement before the shader snapshots it. Three's render-time
 * update happens after useFrame; relying on it puts terrain a frame behind
 * the markers, especially during damped orbiting and keyboard navigation. */
export function updateCameraUniforms(uniforms: Record<string, IUniform>, camera: Camera): void {
  camera.updateMatrixWorld();
  uniforms.uCameraPosition.value.copy(camera.position);
  uniforms.uProjectionMatrix.value.copy(camera.projectionMatrix);
  uniforms.uViewMatrix.value.copy(camera.matrixWorldInverse);
  uniforms.uInvProjectionMatrix.value.copy(camera.projectionMatrixInverse);
  uniforms.uInvViewMatrix.value.copy(camera.matrixWorld);
}
