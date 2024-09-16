import { z } from 'zod';

export const SettingSchema = z.object({
  key: z.string(),
  value: z.any(),
  description: z.string()
});

export type Setting = z.infer<typeof SettingSchema>;