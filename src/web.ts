import { WebPlugin } from '@capacitor/core';

import type { VideoCompressorPlugin } from './definitions';

export class VideoCompressorWeb extends WebPlugin implements VideoCompressorPlugin {
  async echo(options: { value: string }): Promise<{ value: string }> {
    console.log('ECHO', options);
    return options;
  }
}
