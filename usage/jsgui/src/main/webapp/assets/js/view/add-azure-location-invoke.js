/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
*/
/**
 * Render Azure location configuration as modal
 */
define([
    "underscore", "jquery", "backbone", "brooklyn-utils",
    "text!tpl/home/add-azure-location-modal.html"
], function(_, $, Backbone, Util, AddAzureLocationHtml) {
    return Backbone.View.extend({
        template: _.template(AddAzureLocationHtml),

        events:{
            "click .start-azure":"onSubmit",
        },

        initialize: function() {
            this.title = "Azure location setup";
        },
        render: function() {
            this.$el.html(this.template({
            }));
            return this;
        },
        onSubmit: function() {
            var dlg = this;
            if (!this.checkFields('subscriptionId', 'certificate', 'certificatePassword', 'consolePassword', 'confirmPassword')) {
                return;
            }
            $("#azure-setup").ajaxSubmit({
                url: '/v1/azure',
                iframe: true,
                success: function(responseText, statusText, xhr, form) {
                    //No access to responseCode (iframe transport)
                    if ("done" == responseText) {
                    	dlg.close();
                        $('body').removeClass('modal-open');
                        $('.modal-backdrop').remove();
                        dlg.options.home.createApplication();
                    } else {
                        dlg.showError("Couldn't connect to Azure, check if the credentials are valid.");
                    }
                }
            });
        },
        checkFields: function() {
            var dlg = this;
            for (var i = 0; i < arguments.length; i++) {
                var label = this.$('label[for="' + arguments[i] + '"]')
                var input = $('#' + label.attr('for'));
                if (!input.val()) {
                    dlg.showError(label.text() + ' must not be empty.');
                    return false;
                }
            }

            var passwordLabel = this.$('label[for="' + arguments[arguments.length - 2] + '"]')
            var passwordText = $('#' + passwordLabel.attr('for'));

            var confirmLabel = this.$('label[for="' + arguments[arguments.length - 1] + '"]')
            var confirmText = $('#' + confirmLabel.attr('for'));

            if (!confirmText.val() || passwordText.val() != confirmText.val()) {
                dlg.showError('Password and confirmation do not match.');
                return false;
            }

            return true;
        },
        showError: function (message) {
            this.$(".dialog-error").removeClass("hide");
            this.$(".dialog-error").html(message);
        }
    });
});