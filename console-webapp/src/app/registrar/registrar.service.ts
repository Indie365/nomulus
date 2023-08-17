// Copyright 2023 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

import { Injectable } from '@angular/core';
import { BackendService } from '../shared/services/backend.service';
import { Subject } from 'rxjs';

interface Address {
  street?: string[];
  city?: string;
  countryCode?: string;
  zip?: string;
  state?: string;
}
export interface Registrar {
  allowedTlds?: string[];
  ipAddressAllowList?: string[];
  emailAddress?: string;
  billingAccountMap?: object;
  driveFolderId?: string;
  ianaIdentifier?: number;
  icannReferralEmail?: string;
  localizedAddress?: Address;
  registrarId: string;
  registrarName: string;
  registryLockAllowed?: boolean;
}
@Injectable({
  providedIn: 'root',
})
export class RegistrarService {
  activeRegistrarId: string = '';
  registrars: Registrar[] = [];
  activeRegistrarIdChange: Subject<string> = new Subject<string>();

  constructor(private backend: BackendService) {
    this.backend.getRegistrars().subscribe((r) => {
      this.registrars = r;
    });
  }

  public updateRegistrar(registrarId: string) {
    this.activeRegistrarId = registrarId;
    this.activeRegistrarIdChange.next(registrarId);
  }
}
