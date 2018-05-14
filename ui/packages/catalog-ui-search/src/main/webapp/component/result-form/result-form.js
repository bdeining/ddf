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
/*global define,window*/

 define([
     'jquery',
     'backbone',
     'underscore'
 ], function ($, Backbone, _) {

  let resultTemplates = [];
  let resultTemplateProperties = [];

  return new (Backbone.Model.extend({
      initialize: function () {
        if (!this.isDestroyed) {
            const customResultTemplates = this.getArrayMap(resultTemplates);
            resultTemplateProperties = customResultTemplates;
        }
      },
      getArrayMap: function (resultList){
        let resultFormTemplates = _.map(resultList, function(resultForm) {
            return {
              label: resultForm.title,
              value: resultForm.id,
              id: resultForm.id,
              descriptors: resultForm.descriptors,
              description: resultForm.description,
              created: resultForm.created,
              creator: resultForm.creator,
              accessGroups: resultForm.accessGroups,
              accessIndividuals: resultForm.accessIndividuals
            };
        });
        resultFormTemplates.push({
            label: 'All Fields',
            value: 'allFields',
            id: 'allFields',
            descriptors: [],
            description: 'All Fields'
          });
        return resultFormTemplates;
      },
      getResultTemplatesProperties: function() {
          return resultTemplateProperties;
      },
      deleteResultTemplateById: function(id) {
        resultTemplateProperties = _.filter(resultTemplateProperties, function(template) {
            return template.id !== id
     })},
     updatesResultTemplates: function(newForms){
        resultTemplateProperties = this.getArrayMap(newForms);
     }
    }));
})
