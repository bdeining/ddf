/**
 * Copyright (c) Codice Foundation
 *
 * This is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. A copy of the GNU Lesser General Public License
 * is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 *
 **/
define([
    'marionette',
    'underscore',
    'jquery',
    '../tabs.view',
    'js/store',
    'properties',
    './tabs-list-add'
], function (Marionette, _, $, TabsView, store, properties, ListAddTabsModel) {

    var ListAddTabsView = TabsView.extend({
        className: 'is-list-add',
        setDefaultModel: function(options){
            this.model = new ListAddTabsModel();
        },
        selectionInterface: store,
        initialize(options){
            this.selectionInterface = options.selectionInterface || store;
            this.setDefaultModel(options);

            TabsView.prototype.initialize.call(this);
            this.model.set('activeTab', 'Import');
        },
        determineContent() {
            var activeTab = this.model.getActiveView();
            if (this.model.attributes.activeTab === 'Import') {
                this.tabsContent.show(new activeTab({
                    isList: true,
                    extraHeaders: this.options.extraHeaders,
                    url: this.options.url,
                    handleUploadSuccess: this.options.handleUploadSuccess
                }));
            } else {
                this.tabsContent.show(new activeTab({
                    handleNewMetacard: this.options.handleNewMetacard,
                    close: this.options.close,
                    model: this.model
                }));
            }
        }
    });

    return ListAddTabsView;
});