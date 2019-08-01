// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

goog.provide('registry.registrar.RegistryLock');

goog.forwardDeclare('registry.registrar.Console');
goog.require('goog.array');
goog.require('goog.dom');
goog.require('goog.dom.classlist');
goog.require('goog.events');
goog.require('goog.events.KeyCodes');
goog.require('goog.events.EventType');
goog.require('goog.json');
goog.require('goog.net.XhrIo');
goog.require('goog.soy');
goog.require('registry.Resource');
goog.require('registry.ResourceComponent');
goog.require('registry.soy.registrar.registrylock');


/**
 * Registry Lock page, allowing the user to lock / unlock domains.
 * @param {!registry.registrar.Console} console
 * @param {!registry.Resource} resource the RESTful resource for the registrar.
 * @constructor
 * @extends {registry.ResourceComponent}
 * @final
 */
registry.registrar.RegistryLock = function(console, resource) {
  registry.registrar.RegistryLock.base(
      this, 'constructor', console, resource,
      registry.soy.registrar.registrylock.settings, false, null);
};
goog.inherits(registry.registrar.RegistryLock, registry.ResourceComponent);

registry.registrar.RegistryLock.prototype.runAfterRender = function(objArgs) {
  // Listen to the "submit" button click as well as the enter key
  var submitButton = goog.dom.getRequiredElement('lock-domain-submit');
  goog.events.listen(submitButton,
                     goog.events.EventType.CLICK,
                     this.onLockDomain_,
                     false,
                     this);
  goog.events.listen(goog.dom.getRequiredElement('lock-domain-input'),
                     goog.events.EventType.KEYUP,
                     this.onInputKeyUp_,
                     false,
                     this);

  var onUnlockClick = this.onUnlockDomain_;
  // Load the existing locks and display them in the table
  goog.net.XhrIo.send('/registrar-domain-lock?clientId=' + objArgs.clientId, function(e) {
    var response =
            /** @type {!registry.json.locks.ExistingLocksResponse} */
            (e.target.getResponseJson(registry.Resource.PARSER_BREAKER_));
    var existingLocksDiv = goog.dom.getRequiredElement('existing-locks-div');
    goog.soy.renderElement(
        existingLocksDiv, registry.soy.registrar.registrylock.existingLocksTable,
        {locks: response.locks});
    // For all unlock buttons, listen and perform the unlock action if they're clicked
    var unlockButtons = goog.dom.getElementsByClass('domain-unlock-button', existingLocksDiv);
    for (let i = 0; i < unlockButtons.length; i++) {
      goog.events.listen(unlockButtons[i], goog.events.EventType.CLICK, onUnlockClick, false, this);
    }
  });
};

/**
 * Shows the lock/unlock confirmation modal
 * @private
 */
registry.registrar.RegistryLock.prototype.showModal_ = function(targetElement, domain, isLock) {
  var parentElement = targetElement.parentElement;
  var modalElement = goog.soy.renderAsElement(registry.soy.registrar.registrylock.confirmModal, {domain: domain, isLock: isLock});
  parentElement.prepend(modalElement);
  goog.dom.getRequiredElement("domain-lock-password").focus();
  // delete the modal when the user clicks the cancel button
  goog.events.listen(goog.dom.getRequiredElement('domain-lock-cancel'),
                     goog.events.EventType.CLICK,
                     function() { parentElement.removeChild(parentElement.firstChild); },
                     false,
                     this);

  // TODO : do the submit
}

/**
 * Click handler for unlocking domains (button click).
 * @private
 */
registry.registrar.RegistryLock.prototype.onUnlockDomain_ = function(e) {
  // the domain is stored in the button ID if it's the right type of button
  var idRegex = /button-unlock-(.*)/
  var targetId = e.target.id;
  var match = targetId.match(idRegex);
  if (match) {
    var domain = match[1];
    this.showModal_(e.target, domain, false);
  }
}

/**
 * Keyup handler for lock-domain input (used for enter -> submit).
 * @private
 */
registry.registrar.RegistryLock.prototype.onInputKeyUp_ = function(e) {
  if (e.keyCode === goog.events.KeyCodes.ENTER) {
    e.preventDefault();
    this.onLockDomain_(e);
  }
}

/**
 * Click handler for lock-domain button.
 * @private
 */
registry.registrar.RegistryLock.prototype.onLockDomain_ = function(e) {
  var domain = goog.dom.getRequiredElement('lock-domain-input').value;
  this.showModal_(e.target, domain, true);
};
