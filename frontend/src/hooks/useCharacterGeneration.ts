import { useCallback, useEffect, useRef, useState } from 'react';
import { useCharacterStore } from '@/stores/characterStore';

/** A generation result belongs only to the editor session that requested it. */
export function useCharacterGeneration() {
  const generate = useCharacterStore((state) => state.generateCharacter);
  const request = useRef<{ id: number; controller: AbortController } | null>(null);
  const version = useRef(0);
  const [isGenerating, setIsGenerating] = useState(false);

  const cancelGeneration = useCallback(() => {
    version.current++;
    request.current?.controller.abort();
    request.current = null;
    setIsGenerating(false);
  }, []);

  useEffect(
    () => () => {
      version.current++;
      request.current?.controller.abort();
      request.current = null;
    },
    [],
  );

  const generateCharacter = useCallback(
    async (prompt: string) => {
      if (request.current) return null;
      const id = ++version.current;
      const controller = new AbortController();
      request.current = { id, controller };
      setIsGenerating(true);
      try {
        const character = await generate(prompt, controller.signal);
        return id === version.current && !controller.signal.aborted ? character : null;
      } finally {
        if (id === version.current) {
          request.current = null;
          setIsGenerating(false);
        }
      }
    },
    [generate],
  );

  return { generateCharacter, cancelGeneration, isGenerating };
}
