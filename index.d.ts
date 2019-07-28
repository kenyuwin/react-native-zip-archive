declare module 'react-native-zip-archive' {
  enum encryptionMethods {
    'STANDARD',
    'AES-128',
    'AES-256'
  }
  import { NativeEventSubscription } from 'react-native';
  export function zip(source: string, target: string): Promise<string>;
<<<<<<< HEAD
  export function unzip(source: string, target: string, charset?: string): Promise<string>;
=======
  export function zipWithPassword(source: string, target: string, passowrd: string, encryptionMethod?: encryptionMethods): Promise<string>;
  export function unzip(source: string, target: string): Promise<string>;
>>>>>>> using cocoapods solely to manage native des on iOS.
  export function unzipWithPassword(assetPath: string, target: string, passowrd: string): Promise<string>;
  export function unzipAssets(assetPath: string, target: string): Promise<string>;
  export function subscribe(callback: ({ progress: number, filePath: string })): NativeEventSubscription;
}
