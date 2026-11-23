/** Character facts needed to design a voice, retained across queueing/restarts. */
export interface VoiceProfile {
  id?: string;
  name?: string;
  personality?: string;
  gender?: string;
  pronouns?: string;
  ageYears?: number;
  description?: string;
  appearance?: string;
}
