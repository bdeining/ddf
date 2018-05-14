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
 /*global require*/
 const Marionette = require('marionette');
 const $ = require('jquery');
 const template = require('../search-form.collection.hbs');
 const SearchFormCollectionView = require('./search-form-sharing.collection.view');
 const SearchFormSharingCollection = require('./search-form-sharing.collection');
 const CustomElements = require('js/CustomElements');
 const LoadingView = require('component/loading/loading.view')

 module.exports = Marionette.LayoutView.extend({
    template: template,
    tagName: CustomElements.register('search-form-sharing-collection'),
    regions: {
        collection: '.collection',
        loadingView: '.loading'
    },
    initialize: function () {
        this.searchFormSharingCollection = new SearchFormSharingCollection();
        this.listenTo(this.searchFormSharingCollection, 'change:doneLoading', this.handleLoadingSpinner);
    },
    onRender: function () {
        this.collection.show(new SearchFormCollectionView({
            collection: this.searchFormSharingCollection.getCollection(),
            model: this.model
        }));
        this.loadingView.show(new LoadingView({ DOMHook: this.$el }))
        this.handleLoadingSpinner();
    },
    showCollection: function() {
        if(this.collection.currentView.searchFormSharingCollection.getDoneLoading()) {
            this.$el.find('.loading').hide();
        }
    },
    handleLoadingSpinner: function() {
        if(this.searchFormSharingCollection.getDoneLoading()) {
            this.loadingView.currentView.remove();
        }
    }
 });