// Kortana's iridescent light-body material — Fresnel rim glow that shifts
// between her opal-white base and a luminous accent color (adjustable at
// runtime via setGlowColor, matching her Iridescent Violet / Moonlight
// Silver / Opal Gold palette from the app's color picker).
import * as THREE from 'three';

export function createKortanaMaterial() {
    return new THREE.ShaderMaterial({
        uniforms: {
            uTime: { value: 0 },
            uBaseColor: { value: new THREE.Color(0xdce8ff) },   // Opal white
            uGlowColor: { value: new THREE.Color(0x8a2be2) },   // Luminous violet
        },
        vertexShader: `
            varying vec3 vNormal;
            varying vec3 vViewPosition;

            void main() {
                vNormal = normalize(normalMatrix * normal);
                vec4 mvPosition = modelViewMatrix * vec4(position, 1.0);
                vViewPosition = -mvPosition.xyz;
                gl_Position = projectionMatrix * mvPosition;
            }
        `,
        fragmentShader: `
            uniform vec3 uBaseColor;
            uniform vec3 uGlowColor;
            uniform float uTime;

            varying vec3 vNormal;
            varying vec3 vViewPosition;

            void main() {
                vec3 normal = normalize(vNormal);
                vec3 viewDir = normalize(vViewPosition);

                // Fresnel rim glow
                float fresnel = pow(1.0 - max(dot(normal, viewDir), 0.0), 2.5);

                // Subtle pulse oscillation — her aura breathing
                float pulse = sin(uTime * 1.5) * 0.15 + 0.85;

                // Opaque base so a multi-part mesh (14 separate pieces on
                // the placeholder body) always depth-sorts correctly — no
                // see-through blending artifacts between overlapping limbs.
                // The glow reads as an emissive rim brightening, not
                // transparency.
                vec3 finalColor = uBaseColor + uGlowColor * fresnel * pulse * 1.4;
                gl_FragColor = vec4(finalColor, 1.0);
            }
        `,
        transparent: false,
        depthWrite: true,
    });
}
