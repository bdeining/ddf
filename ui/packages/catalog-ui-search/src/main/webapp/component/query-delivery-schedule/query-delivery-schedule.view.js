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
/*global define, setTimeout*/
define([
    'marionette',
    'backbone',
    'js/store',
    'component/singletons/user-instance',
    'js/CustomElements',
    './query-delivery-schedule.hbs',
    'component/property/property.view',
    'component/property/property'
], function(Marionette, Backbone, store, user, CustomElements, template, PropertyView, Property) {
    
    return Marionette.LayoutView.extend({
        template: template,
        tagName: CustomElements.register('query-delivery-schedule'),
        regions: {
            deliverySchedule: '.enable-delivery-scheduling',
            scheduleProperties: '.delivery-schedule-properties',
            hoursPicker: '.hours-picker',
            minutesPicker: '.minutes-picker',
            deliveryPicker: '.delivery-method-picker'
        },
        initialize() {
            const possibleModel = this.model && this.model.id ? store.getDeliveryById(this.model.id) : this.model;
            this.model = possibleModel || this.model
        },
        onBeforeShow() {
            this.turnOffEditing();
            this.setupRegions();
            this.listenTo(this.deliverySchedule.currentView.model, 'change:value', this.handleSchedulingDeliveryValue);
            this.turnOnEditing();
        },
        setupRegions() {
            this.deliverySchedule.show(new PropertyView({
                model: new Property({
                    value: [this.model.get('deliveryScheduled')],
                    id: 'Delivery Schedule',
                    radio: [{
                        label: 'Immediately',
                        value: false,
                        title: 'Place Orders Immediately'
                    }, {
                        label: 'At a schedule time',
                        value: true,
                        title: 'Run Orders at a future time'
                    }]
                })
            }));
            this.deliverySchedule.currentView.turnOnLimitedWidth();
            this.handleSchedulingDeliveryValue();

            this.hoursPicker.show(new PropertyView({
                model: new Property({
                    value: [this.model.get('hours')],
                    id: '',
                    enum: [{
                        label: '00',
                        value: 0
                    }, {
                        label: '01',
                        value: 1
                    }, {
                        label: '02',
                        value: 2
                    }, {
                        label: '03',
                        value: 3
                    }, {
                        label: '04',
                        value: 4
                    }, {
                        label: '05',
                        value: 5
                    }, {
                        label: '06',
                        value: 6
                    }, {
                        label: '07',
                        value: 7
                    }, {
                        label: '08',
                        value: 8
                    }, {
                        label: '09',
                        value: 9
                    }, {
                        label: '10',
                        value: 10
                    }, {
                        label: '11',
                        value: 11
                    }, {
                        label: '12',
                        value: 12
                    }, {
                        label: '13',
                        value: 13
                    }, {
                        label: '14',
                        value: 14
                    }, {
                        label: '15',
                        value: 15
                    }, {
                        label: '16',
                        value: 16
                    }, {
                        label: '17',
                        value: 17
                    }, {
                        label: '18',
                        value: 18
                    }, {
                        label: '19',
                        value: 19
                    }, {
                        label: '20',
                        value: 20
                    }, {
                        label: '21',
                        value: 21
                    }, {
                        label: '22',
                        value: 22
                    }, {
                        label: '23',
                        value: 23
                    }],
                    showValidationIssues: false,
                    showLabel: false
                })
            }));
            this.hoursPicker.currentView.turnOnLimitedWidth();

            this.minutesPicker.show(new PropertyView({
                model: new Property({
                    value: [this.model.get('minutes')],
                    id: '',
                    enum: [{
                        label: '00',
                        value: 0
                    }, {
                        label: '01',
                        value: 1
                    }, {
                        label: '02',
                        value: 2
                    }, {
                        label: '03',
                        value: 3
                    }, {
                        label: '04',
                        value: 4
                    }, {
                        label: '05',
                        value: 5
                    }, {
                        label: '06',
                        value: 6
                    }, {
                        label: '07',
                        value: 7
                    }, {
                        label: '08',
                        value: 8
                    }, {
                        label: '09',
                        value: 9
                    }, {
                        label: '10',
                        value: 10
                    }, {
                        label: '11',
                        value: 11
                    }, {
                        label: '12',
                        value: 12
                    }, {
                        label: '13',
                        value: 13
                    }, {
                        label: '14',
                        value: 14
                    }, {
                        label: '15',
                        value: 15
                    }, {
                        label: '16',
                        value: 16
                    }, {
                        label: '17',
                        value: 17
                    }, {
                        label: '18',
                        value: 18
                    }, {
                        label: '19',
                        value: 19
                    }, {
                        label: '20',
                        value: 20
                    }, {
                        label: '21',
                        value: 21
                    }, {
                        label: '22',
                        value: 22
                    }, {
                        label: '23',
                        value: 23
                    }, {
                        label: '24',
                        value: 24
                    }, {
                        label: '25',
                        value: 25
                    }, {
                        label: '26',
                        value: 26
                    }, {
                        label: '27',
                        value: 27
                    }, {
                        label: '28',
                        value: 28
                    }, {
                        label: '29',
                        value: 29
                    }, {
                        label: '30',
                        value: 30
                    }, {
                        label: '31',
                        value: 31
                    }, {
                        label: '32',
                        value: 32
                    }, {
                        label: '33',
                        value: 33
                    }, {
                        label: '34',
                        value: 34
                    }, {
                        label: '35',
                        value: 35
                    }, {
                        label: '36',
                        value: 36
                    }, {
                        label: '37',
                        value: 37
                    }, {
                        label: '38',
                        value: 38
                    }, {
                        label: '39',
                        value: 39
                    }, {
                        label: '40',
                        value: 40
                    }, {
                        label: '41',
                        value: 41
                    }, {
                        label: '42',
                        value: 42
                    }, {
                        label: '43',
                        value: 43
                    }, {
                        label: '44',
                        value: 44
                    }, {
                        label: '45',
                        value: 45
                    }, {
                        label: '46',
                        value: 46
                    }, {
                        label: '47',
                        value: 47
                    }, {
                        label: '48',
                        value: 48
                    }, {
                        label: '49',
                        value: 49
                    }, {
                        label: '50',
                        value: 50
                    }, {
                        label: '51',
                        value: 51
                    }, {
                        label: '52',
                        value: 52
                    }, {
                        label: '53',
                        value: 53
                    }, {
                        label: '54',
                        value: 54
                    }, {
                        label: '55',
                        value: 55
                    }, {
                        label: '56',
                        value: 56
                    }, {
                        label: '57',
                        value: 57
                    }, {
                        label: '58',
                        value: 58
                    }, {
                        label: '59',
                        value: 59
                    }],
                    showValidationIssues: false,
                    showLabel: false
                })
            }));
            this.minutesPicker.currentView.turnOnLimitedWidth();

            const possibleEnumValues = user.get('user').getPreferences().get('deliveryMethods')
                .map(function(val) {
                    return {
                        label: val.get('name'), 
                        value: val.get('deliveryId'), 
                        class: ''
                    }
                });
            
            this.deliveryPicker.show(new PropertyView({
                model: new Property({
                    enumFiltering: false,
                    showValidationIssues: false,
                    enumMulti: true,
                    enum: possibleEnumValues,
                    value: [this.model.get('deliveryIds')],
                    id: 'Delivery Method'
                })
            }));
            this.deliveryPicker.currentView.turnOnLimitedWidth();
        },
        turnOnEditing() {
            this.$el.addClass('is-editing');
            this.regionManager.forEach(function(region) {
                if (region.currentView) {
                    region.currentView.turnOnEditing();
                }
            });
        },
        turnOffEditing() {
            this.regionManager.forEach(function(region) {
                if (region.currentView) {
                    region.currentView.turnOffEditing();
                }
            });
        },
        handleSchedulingDeliveryValue() {
            const isSchedulingDelivery = this.deliverySchedule.currentView.model.getValue()[0];
            this.$el.toggleClass('is-scheduled', isSchedulingDelivery);
        },
        getDataModel() {
            this.model.set({
                deliveryScheduled: this.deliverySchedule.currentView.model.getValue()[0],
                hours: this.hoursPicker.currentView.model.getValue()[0],
                minutes: this.minutesPicker.currentView.model.getValue()[0],
                deliveryIds: this.deliveryPicker.currentView.model.getValue()[0]
            });
            return this.model;
        }
    })
});