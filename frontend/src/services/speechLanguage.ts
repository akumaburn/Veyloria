export const SPEECH_LANGUAGES = [
  { value: 'en', label: 'English' }, { value: 'zh', label: 'Chinese' },
  { value: 'de', label: 'German' }, { value: 'es', label: 'Spanish' },
  { value: 'fr', label: 'French' }, { value: 'ko', label: 'Korean' },
  { value: 'ja', label: 'Japanese' },
] as const;
export type SpeechLanguage = typeof SPEECH_LANGUAGES[number]['value'];
export const NARRATOR_PREVIEWS: Record<SpeechLanguage, string> = {
  en: 'The lantern glows beside the quiet doorway.',
  zh: '灯笼在安静的门旁发出柔和的光。',
  de: 'Die Laterne leuchtet neben der stillen Tür.',
  es: 'La linterna brilla junto a la puerta silenciosa.',
  fr: 'La lanterne éclaire doucement la porte.',
  ko: '고요한 문 옆에서 등불이 빛납니다.',
  ja: '静かな扉のそばで、ランタンが優しく光っています。',
};
