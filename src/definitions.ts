export interface VideoCompressorPlugin {
  echo(options: { value: string }): Promise<{ value: string }>;
}
